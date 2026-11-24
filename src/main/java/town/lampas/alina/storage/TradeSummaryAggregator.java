package town.lampas.alina.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import town.lampas.alina.storage.TradeSummary.Concentration;
import town.lampas.alina.storage.TradeSummary.DailyTotals;
import town.lampas.alina.storage.TradeSummary.ItemFlow;
import town.lampas.alina.storage.TradeSummary.OfferFlow;
import town.lampas.alina.storage.TradeSummary.OperationHealth;
import town.lampas.alina.storage.TradeSummary.ShopTotals;

/**
 * Computes {@link TradeSummary} identically from the snapshot rows read by either {@link InMemoryStorage} or
 * {@link PostgresStorage}.
 */
final class TradeSummaryAggregator {

    record TradeRow(UUID characterId, long revisionId, OperationEffect.TradeSettled effect) {}

    record BudgetRow(Identifier shopId, int aurPaid) {}

    private TradeSummaryAggregator() {}

    static TradeSummary aggregate(
            Map<Identifier, Integer> shopBudgets,
            int topLimit,
            List<TradeRow> trades,
            List<BudgetRow> budgets,
            OperationHealth health) {
        Map<LocalDate, DayAcc> days = new TreeMap<>();
        Map<Identifier, ShopAcc> shops = new HashMap<>();
        for (Map.Entry<Identifier, Integer> entry : shopBudgets.entrySet()) {
            shops.computeIfAbsent(entry.getKey(), id -> new ShopAcc(id, entry.getValue()));
        }
        Map<String, ItemAcc> soldItems = new HashMap<>();
        Map<OfferKey, OfferAcc> boughtOffers = new HashMap<>();
        Map<UUID, Long> earnerAur = new HashMap<>();

        for (TradeRow row : trades) {
            OperationEffect.TradeSettled effect = row.effect();
            LocalDate tradeDay = effect.tradeDay();
            Identifier shopId = effect.shopId();
            long aurPaid = effect.aurPaid();

            JsonObject detail = JsonParser.parseString(effect.detailJson()).getAsJsonObject();
            long xpSold = longField(detail, "experience_sold");
            long xpBought = longField(detail, "experience_bought");
            long aurSpent = longField(detail, "aur_spent");

            long mintedItems = (xpSold == 0 && aurPaid > 0) ? aurPaid : 0;
            long mintedXp = (xpSold > 0 && aurPaid > 0) ? aurPaid : 0;
            long sunkOffers = (xpBought == 0) ? aurSpent : 0;
            long sunkXp = (xpBought > 0) ? aurSpent : 0;

            DayAcc day = days.computeIfAbsent(tradeDay, ignored -> new DayAcc());
            day.tradeCount++;
            day.traders.add(row.characterId());
            day.aurMintedItems += mintedItems;
            day.aurMintedXp += mintedXp;
            day.aurSunkOffers += sunkOffers;
            day.aurSunkXp += sunkXp;
            day.xpPointsSold += xpSold;
            day.xpPointsBought += xpBought;
            day.revisionIds.add(row.revisionId());

            ShopAcc shop = shops.computeIfAbsent(shopId, id -> new ShopAcc(id, shopBudgets.getOrDefault(id, 0)));
            shop.tradeCount++;
            shop.traders.add(row.characterId());
            shop.aurMinted += aurPaid;
            shop.aurSunk += aurSpent;
            shop.xpPointsSold += xpSold;
            shop.xpPointsBought += xpBought;

            if (aurPaid > 0) {
                earnerAur.merge(row.characterId(), aurPaid, Long::sum);
            }

            collectSold(detail, soldItems);
            collectBought(shopId, detail, boughtOffers);
        }

        for (BudgetRow budget : budgets) {
            Identifier shopId = budget.shopId();
            ShopAcc shop = shops.computeIfAbsent(shopId, id -> new ShopAcc(id, shopBudgets.getOrDefault(id, 0)));
            int aurPaid = budget.aurPaid();
            int dailyBudget = shop.dailyBudget;
            shop.playerDaysActive++;
            if (dailyBudget > 0) {
                if (aurPaid * 10L >= dailyBudget * 9L) {
                    shop.playerDaysNearCap++;
                }
                if (aurPaid >= dailyBudget) {
                    shop.playerDaysAtCap++;
                }
                shop.bpsSum += Math.min(10_000L, (aurPaid * 10_000L) / dailyBudget);
            }
            if (aurPaid > shop.maxPlayerDayAurPaid) {
                shop.maxPlayerDayAurPaid = aurPaid;
            }
        }

        List<DailyTotals> dailyList = new ArrayList<>(days.size());
        for (Map.Entry<LocalDate, DayAcc> entry : days.entrySet()) {
            DayAcc d = entry.getValue();
            dailyList.add(new DailyTotals(
                    entry.getKey(),
                    d.tradeCount,
                    d.traders.size(),
                    d.aurMintedItems,
                    d.aurMintedXp,
                    d.aurSunkOffers,
                    d.aurSunkXp,
                    d.xpPointsSold,
                    d.xpPointsBought,
                    List.copyOf(d.revisionIds)));
        }

        List<ShopTotals> shopList = shops.values().stream()
                .map(ShopAcc::toTotals)
                .sorted(Comparator
                        .comparingLong((ShopTotals s) -> s.aurMinted() + s.aurSunk()).reversed()
                        .thenComparing(s -> s.shopId().toString()))
                .toList();

        List<ItemFlow> topSold = soldItems.values().stream()
                .map(ItemAcc::toFlow)
                .sorted(Comparator
                        .comparingLong(ItemFlow::aur).reversed()
                        .thenComparing(Comparator.comparingLong(ItemFlow::count).reversed())
                        .thenComparing(ItemFlow::item))
                .limit(topLimit)
                .toList();

        List<OfferFlow> topBought = boughtOffers.values().stream()
                .map(OfferAcc::toFlow)
                .sorted(Comparator
                        .comparingLong(OfferFlow::aur).reversed()
                        .thenComparing(Comparator.comparingLong(OfferFlow::count).reversed())
                        .thenComparing(OfferFlow::item)
                        .thenComparing(o -> o.shopId().toString())
                        .thenComparing(o -> o.offerId().orElse("")))
                .limit(topLimit)
                .toList();

        Concentration concentration = concentrationOf(earnerAur);
        return new TradeSummary(dailyList, shopList, topSold, topBought, concentration, health);
    }

    private static void collectSold(JsonObject detail, Map<String, ItemAcc> soldItems) {
        JsonElement element = detail.get("sold");
        if (element == null || !element.isJsonArray()) {
            return;
        }
        Set<String> seenInTrade = new HashSet<>();
        JsonArray array = element.getAsJsonArray();
        for (JsonElement entryEl : array) {
            if (!entryEl.isJsonObject()) {
                continue;
            }
            JsonObject entry = entryEl.getAsJsonObject();
            String item = stringField(entry, "item");
            if (item == null) {
                continue;
            }
            long count = longField(entry, "count");
            long aur = longField(entry, "aur");
            ItemAcc acc = soldItems.computeIfAbsent(item, ItemAcc::new);
            acc.count += count;
            acc.aur += aur;
            if (seenInTrade.add(item)) {
                acc.tradeCount++;
            }
        }
    }

    private static void collectBought(Identifier shopId, JsonObject detail, Map<OfferKey, OfferAcc> boughtOffers) {
        JsonElement element = detail.get("bought");
        if (element == null || !element.isJsonArray()) {
            return;
        }
        Set<OfferKey> seenInTrade = new HashSet<>();
        JsonArray array = element.getAsJsonArray();
        for (JsonElement entryEl : array) {
            if (!entryEl.isJsonObject()) {
                continue;
            }
            JsonObject entry = entryEl.getAsJsonObject();
            String item = stringField(entry, "item");
            if (item == null) {
                continue;
            }
            Optional<String> offerId = Optional.ofNullable(stringField(entry, "offer"));
            long count = longField(entry, "count");
            long aur = longField(entry, "aur");
            OfferKey key = new OfferKey(shopId, offerId, item);
            OfferAcc acc = boughtOffers.computeIfAbsent(key, OfferAcc::new);
            acc.count += count;
            acc.aur += aur;
            if (seenInTrade.add(key)) {
                acc.tradeCount++;
            }
        }
    }

    private static Concentration concentrationOf(Map<UUID, Long> earnerAur) {
        if (earnerAur.isEmpty()) {
            return new Concentration(0, 0, 0, 0);
        }
        List<Long> sorted = earnerAur.values().stream().sorted(Comparator.reverseOrder()).toList();
        long total = 0;
        for (long v : sorted) {
            total += v;
        }
        int top5Count = Math.min(5, sorted.size());
        long top5 = 0;
        for (int i = 0; i < top5Count; i++) {
            top5 += sorted.get(i);
        }
        int top10PctCount = Math.max(1, (int) Math.ceil(sorted.size() * 0.10));
        long top10Pct = 0;
        for (int i = 0; i < top10PctCount; i++) {
            top10Pct += sorted.get(i);
        }
        return new Concentration(total, sorted.size(), top5, top10Pct);
    }

    private static long longField(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        return element.getAsLong();
    }

    private static String stringField(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    private static final class DayAcc {
        int tradeCount;
        final Set<UUID> traders = new HashSet<>();
        long aurMintedItems;
        long aurMintedXp;
        long aurSunkOffers;
        long aurSunkXp;
        long xpPointsSold;
        long xpPointsBought;
        final Set<Long> revisionIds = new TreeSet<>();
    }

    private static final class ShopAcc {
        final Identifier shopId;
        final int dailyBudget;
        int tradeCount;
        final Set<UUID> traders = new HashSet<>();
        long aurMinted;
        long aurSunk;
        long xpPointsSold;
        long xpPointsBought;
        int playerDaysActive;
        int playerDaysNearCap;
        int playerDaysAtCap;
        long bpsSum;
        int maxPlayerDayAurPaid;

        ShopAcc(Identifier shopId, int dailyBudget) {
            this.shopId = shopId;
            this.dailyBudget = dailyBudget;
        }

        ShopTotals toTotals() {
            int avgBps = (dailyBudget <= 0 || playerDaysActive == 0) ? 0 : (int) (bpsSum / playerDaysActive);
            return new ShopTotals(
                    shopId,
                    dailyBudget,
                    tradeCount,
                    traders.size(),
                    aurMinted,
                    aurSunk,
                    xpPointsSold,
                    xpPointsBought,
                    playerDaysActive,
                    playerDaysNearCap,
                    playerDaysAtCap,
                    avgBps,
                    maxPlayerDayAurPaid);
        }
    }

    private static final class ItemAcc {
        final String item;
        long count;
        long aur;
        int tradeCount;

        ItemAcc(String item) {
            this.item = item;
        }

        ItemFlow toFlow() {
            return new ItemFlow(item, count, aur, tradeCount);
        }
    }

    private record OfferKey(Identifier shopId, Optional<String> offerId, String item) {}

    private static final class OfferAcc {
        final OfferKey key;
        long count;
        long aur;
        int tradeCount;

        OfferAcc(OfferKey key) {
            this.key = key;
        }

        OfferFlow toFlow() {
            return new OfferFlow(key.shopId(), key.offerId(), key.item(), count, aur, tradeCount);
        }
    }
}
