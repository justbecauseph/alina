package town.lampas.alina.trade;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import town.lampas.alina.content.TradeContent.Denomination;
import town.lampas.alina.content.TradeContent.ExperienceFees;
import town.lampas.alina.content.TradeContent.ExperienceRates;
import town.lampas.alina.content.TradeContent.Offer;
import town.lampas.alina.content.TradeContent.Rate;
import town.lampas.alina.content.TradeContent.Shop;
import town.lampas.alina.storage.OperationEffect.TradeSettled.Purchase;
import town.lampas.alina.trade.TradeRefusal.Reason;
import town.lampas.alina.trade.pricing.InventoryFit;
import town.lampas.alina.trade.pricing.ReserveCurve;
import town.lampas.alina.trade.pricing.ReserveCurve.Reserves;
import town.lampas.alina.trade.pricing.SellPlanner;
import town.lampas.alina.trade.pricing.Take;
import town.lampas.alina.trade.pricing.Wallet;

/**
 * Plans each trade request against the main inventory and the shop's content, checking in the order the trade refuses them.
 * It changes nothing; {@link TradeService} submits the plan to settlement. Numbers are checked before any arithmetic that
 * could overflow an int. A rate of {@code points} for every {@code aur} Aur converts an experience request of
 * {@code aur} Aur, which must be a positive multiple of the rate's {@code aur}, into {@code (aur / rate.aur) * points}
 * points, in both directions. A shop with experience fees trades against the experience market along
 * {@link ReserveCurve}, and each of its points and Aur figures is what the curve gives.
 */
final class TradePlanner {
    /** The most purchases one request may buy. */
    static final int MAX_PURCHASES = 64;
    /** The most experience points a player holds: vanilla keeps them in an int. */
    static final long MAX_POINTS = Integer.MAX_VALUE;
    /** The largest count of one stack: {@code ItemStack.CODEC} refuses more. */
    private static final int MAX_PIECE = 99;

    private TradePlanner() {}

    /** Sells {@code count} items of the main inventory stack in {@code slot} for {@code expectedAur}. */
    static TradePlan sell(Shop shop, List<Denomination> currency, Inventory inventory, int budgetLeft, int slot,
            int count, int expectedAur) {
        if (slot < 0 || slot >= inventory.getNonEquipmentItems().size() || count < 1) {
            return TradePlan.refuse(Reason.INVALID);
        }
        ItemStack stack = inventory.getItem(slot);
        if (count > stack.getCount()) {
            return TradePlan.refuse(Reason.INVALID);
        }
        Optional<SellPlanner.Quote> quote = SellPlanner.quote(shop, currency, stack);
        if (quote.isEmpty()) {
            return TradePlan.refuse(Reason.STALE);
        }
        int per = quote.get().per();
        if (count % per != 0) {
            return TradePlan.refuse(Reason.INVALID);
        }
        long aur = (long) (count / per) * quote.get().aur();
        if (aur != expectedAur) {
            return TradePlan.refuse(Reason.STALE);
        }
        if (aur > budgetLeft) {
            return TradePlan.refuse(Reason.BUDGET_SPENT);
        }
        List<Take> takes = List.of(new Take(slot, count));
        List<ItemStack> grant = Wallet.coins(currency, aur);
        if (!InventoryFit.fits(inventory, takes, grant)) {
            return TradePlan.refuse(Reason.NO_ROOM);
        }
        JsonArray sold = new JsonArray();
        sold.add(entry(stack, count, aur));
        return new TradePlan(Optional.empty(), "sold", takes, 0, grant, 0, (int) aur, Optional.empty(),
                detail(sold, new JsonArray(), 0, 0, 0), 0, 0);
    }

    /** Sells what "Sell all junk" would sell now, for {@code expectedAur}. */
    static TradePlan junk(Shop shop, List<Denomination> currency, Inventory inventory, int budgetLeft, int expectedAur) {
        SellPlanner.JunkPlan junk = SellPlanner.junk(shop, currency, inventory, budgetLeft);
        if (junk.items() == 0) {
            boolean spent = budgetLeft <= 0 && holdsJunk(shop, currency, inventory);
            return TradePlan.refuse(spent ? Reason.BUDGET_SPENT : Reason.NOTHING_TO_SELL);
        }
        if (junk.aur() != expectedAur) {
            return TradePlan.refuse(Reason.STALE);
        }
        List<ItemStack> grant = Wallet.coins(currency, junk.aur());
        if (!InventoryFit.fits(inventory, junk.takes(), grant)) {
            return TradePlan.refuse(Reason.NO_ROOM);
        }
        JsonArray sold = new JsonArray();
        for (Take take : junk.takes()) {
            sold.add(itemEntry(inventory.getItem(take.slot()), take.count()));
        }
        return new TradePlan(Optional.empty(), "sold", junk.takes(), 0, grant, 0, junk.aur(), Optional.empty(),
                detail(sold, new JsonArray(), 0, 0, 0), 0, 0);
    }

    /**
     * Buys {@code count} purchases of the offer for {@code expectedCost}. The limit and the requirements are checked after
     * the player can pay, as the trade refuses them in that order.
     */
    static TradePlan buy(Shop shop, List<Denomination> currency, Inventory inventory, OfferState state, int count,
            int expectedCost) {
        Offer offer = state.offer();
        if (count < 1 || count > MAX_PURCHASES) {
            return TradePlan.refuse(Reason.INVALID);
        }
        long cost = (long) offer.price() * count;
        if (cost > Integer.MAX_VALUE) {
            return TradePlan.refuse(Reason.INVALID);
        }
        if (cost != expectedCost) {
            return TradePlan.refuse(Reason.STALE);
        }
        Optional<Wallet.Payment> payment = Wallet.plan(inventory, currency, (int) cost);
        if (payment.isEmpty()) {
            return TradePlan.refuse(Reason.CANNOT_AFFORD);
        }
        if (state.limitLeft() >= 0 && count > state.limitLeft()) {
            return TradePlan.refuse(Reason.LIMIT_REACHED);
        }
        if (state.locked().isPresent()) {
            return TradePlan.refuse(Reason.LOCKED);
        }
        ItemStack item = offer.item();
        long items = (long) item.getCount() * count;
        List<ItemStack> grant = new ArrayList<>(pieces(item, items));
        grant.addAll(Wallet.coins(currency, payment.get().change()));
        if (!InventoryFit.fits(inventory, payment.get().takes(), grant)) {
            return TradePlan.refuse(Reason.NO_ROOM);
        }
        Optional<Purchase> purchase = state.period().map(period -> new Purchase(offer.id(), period, count));
        JsonArray bought = new JsonArray();
        bought.add(entry(item, items, cost));
        return new TradePlan(Optional.empty(), "bought", payment.get().takes(), 0, grant, 0, 0, purchase,
                detail(new JsonArray(), bought, 0, 0, cost), 0, 0);
    }

    /**
     * Sells the player's experience for {@code aur} Aur when {@code selling}, or buys experience for {@code aur} Aur. The
     * points are those the shop gives, and {@code expectedPoints} must be them. {@code held} is the player's points.
     * {@code market} is the experience market, empty while it has not loaded; a shop with experience fees trades against it
     * and refuses with {@link Reason#LOADING} without it.
     */
    static TradePlan experience(Shop shop, List<Denomination> currency, Inventory inventory, int budgetLeft, long held,
            boolean selling, int aur, int expectedPoints, Optional<ExperienceMarket.Snapshot> market) {
        if (shop.experienceFees().isPresent()) {
            if (market.isEmpty()) {
                return TradePlan.refuse(Reason.LOADING);
            }
            return selling
                    ? sellAtMarket(shop.experienceFees().get(), currency, inventory, budgetLeft, held, aur, expectedPoints,
                            market.get())
                    : buyAtMarket(shop.experienceFees().get(), currency, inventory, held, aur, expectedPoints, market.get());
        }
        if (aur < 1) {
            return TradePlan.refuse(Reason.INVALID);
        }
        return selling
                ? sellExperience(shop, currency, inventory, budgetLeft, held, aur, expectedPoints)
                : buyExperience(shop, currency, inventory, held, aur, expectedPoints);
    }

    private static TradePlan sellExperience(Shop shop, List<Denomination> currency, Inventory inventory, int budgetLeft,
            long held, int aur, int expectedPoints) {
        Optional<Rate> rate = shop.experience().flatMap(ExperienceRates::sell);
        if (rate.isEmpty()) {
            return TradePlan.refuse(Reason.NOT_OFFERED);
        }
        if (aur % rate.get().aur() != 0) {
            return TradePlan.refuse(Reason.INVALID);
        }
        long points = (long) (aur / rate.get().aur()) * rate.get().points();
        if (points > MAX_POINTS) {
            return TradePlan.refuse(Reason.INVALID);
        }
        if (points != expectedPoints) {
            return TradePlan.refuse(Reason.STALE);
        }
        if (aur > budgetLeft) {
            return TradePlan.refuse(Reason.BUDGET_SPENT);
        }
        if (held < points) {
            return TradePlan.refuse(Reason.NOT_ENOUGH_EXPERIENCE);
        }
        List<ItemStack> grant = Wallet.coins(currency, aur);
        if (!InventoryFit.fits(inventory, List.of(), grant)) {
            return TradePlan.refuse(Reason.NO_ROOM);
        }
        return new TradePlan(Optional.empty(), "sold", List.of(), (int) points, grant, 0, aur, Optional.empty(),
                detail(new JsonArray(), new JsonArray(), (int) points, 0, 0), 0, 0);
    }

    private static TradePlan buyExperience(Shop shop, List<Denomination> currency, Inventory inventory, long held,
            int aur, int expectedPoints) {
        Optional<Rate> rate = shop.experience().flatMap(ExperienceRates::buy);
        if (rate.isEmpty()) {
            return TradePlan.refuse(Reason.NOT_OFFERED);
        }
        if (aur % rate.get().aur() != 0) {
            return TradePlan.refuse(Reason.INVALID);
        }
        long points = (long) (aur / rate.get().aur()) * rate.get().points();
        if (points < 1 || points > MAX_POINTS - held) {
            return TradePlan.refuse(Reason.INVALID);
        }
        if (points != expectedPoints) {
            return TradePlan.refuse(Reason.STALE);
        }
        Optional<Wallet.Payment> payment = Wallet.plan(inventory, currency, aur);
        if (payment.isEmpty()) {
            return TradePlan.refuse(Reason.CANNOT_AFFORD);
        }
        List<ItemStack> grant = Wallet.coins(currency, payment.get().change());
        if (!InventoryFit.fits(inventory, payment.get().takes(), grant)) {
            return TradePlan.refuse(Reason.NO_ROOM);
        }
        return new TradePlan(Optional.empty(), "bought", payment.get().takes(), 0, grant, (int) points, 0,
                Optional.empty(), detail(new JsonArray(), new JsonArray(), 0, (int) points, aur), 0, 0);
    }

    /**
     * The player sells experience to the market for {@code aur} Aur. The points are the curve's for the sell fee, and they are
     * taken from the player and added to the market's points reserve; the Aur comes out of its Aur reserve.
     */
    private static TradePlan sellAtMarket(ExperienceFees fees, List<Denomination> currency, Inventory inventory,
            int budgetLeft, long held, int aur, int expectedPoints, ExperienceMarket.Snapshot market) {
        Reserves reserves = market.live();
        if (fees.sell().isEmpty()) {
            return TradePlan.refuse(Reason.NOT_OFFERED);
        }
        if (aur < 1 || aur > ReserveCurve.maxSellAur(reserves, market.maxTradePercent())) {
            return TradePlan.refuse(Reason.INVALID);
        }
        OptionalInt sold = ReserveCurve.sellPoints(reserves, aur, fees.sell().getAsInt());
        if (sold.isEmpty() || ReserveCurve.afterSell(reserves, sold.getAsInt(), aur).isEmpty()) {
            return TradePlan.refuse(Reason.INVALID);
        }
        int points = sold.getAsInt();
        if (points != expectedPoints) {
            return TradePlan.refuse(Reason.STALE);
        }
        if (aur > budgetLeft) {
            return TradePlan.refuse(Reason.BUDGET_SPENT);
        }
        if (held < points) {
            return TradePlan.refuse(Reason.NOT_ENOUGH_EXPERIENCE);
        }
        List<ItemStack> grant = Wallet.coins(currency, aur);
        if (!InventoryFit.fits(inventory, List.of(), grant)) {
            return TradePlan.refuse(Reason.NO_ROOM);
        }
        return new TradePlan(Optional.empty(), "sold", List.of(), points, grant, 0, aur, Optional.empty(),
                detail(new JsonArray(), new JsonArray(), points, 0, 0), points, -aur);
    }

    /**
     * The player buys experience from the market for {@code aur} Aur. The points are the curve's for the buy fee, and they are
     * taken from the market's points reserve; the Aur goes into its Aur reserve.
     */
    private static TradePlan buyAtMarket(ExperienceFees fees, List<Denomination> currency, Inventory inventory, long held,
            int aur, int expectedPoints, ExperienceMarket.Snapshot market) {
        Reserves reserves = market.live();
        if (fees.buy().isEmpty()) {
            return TradePlan.refuse(Reason.NOT_OFFERED);
        }
        if (aur < 1 || aur > ReserveCurve.maxBuyAur(reserves, market.maxTradePercent())) {
            return TradePlan.refuse(Reason.INVALID);
        }
        OptionalInt bought = ReserveCurve.buyPoints(reserves, aur, fees.buy().getAsInt());
        if (bought.isEmpty() || ReserveCurve.afterBuy(reserves, bought.getAsInt(), aur).isEmpty()
                || bought.getAsInt() > MAX_POINTS - held) {
            return TradePlan.refuse(Reason.INVALID);
        }
        int points = bought.getAsInt();
        if (points != expectedPoints) {
            return TradePlan.refuse(Reason.STALE);
        }
        Optional<Wallet.Payment> payment = Wallet.plan(inventory, currency, aur);
        if (payment.isEmpty()) {
            return TradePlan.refuse(Reason.CANNOT_AFFORD);
        }
        List<ItemStack> grant = Wallet.coins(currency, payment.get().change());
        if (!InventoryFit.fits(inventory, payment.get().takes(), grant)) {
            return TradePlan.refuse(Reason.NO_ROOM);
        }
        return new TradePlan(Optional.empty(), "bought", payment.get().takes(), 0, grant, points, 0, Optional.empty(),
                detail(new JsonArray(), new JsonArray(), 0, points, aur), -points, aur);
    }

    /** The stacks that {@code total} items of {@code item} make: each of the item's max stack size, and at most 99. */
    static List<ItemStack> pieces(ItemStack item, long total) {
        int limit = Math.clamp(item.getMaxStackSize(), 1, MAX_PIECE);
        List<ItemStack> pieces = new ArrayList<>();
        long left = total;
        while (left > 0) {
            int piece = (int) Math.min(limit, left);
            pieces.add(item.copyWithCount(piece));
            left -= piece;
        }
        return pieces;
    }

    /** The Aur the player paid the shop in the trade a log detail describes. Throws when the detail is not that object. */
    static int aurSpentIn(String detail) {
        return JsonParser.parseString(detail).getAsJsonObject().get("aur_spent").getAsInt();
    }

    /** Whether any main inventory stack is one the shop takes as junk. */
    private static boolean holdsJunk(Shop shop, List<Denomination> currency, Inventory inventory) {
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            Optional<SellPlanner.Quote> quote = SellPlanner.quote(shop, currency, inventory.getItem(slot));
            if (quote.isPresent() && quote.get().rule().junk()) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject entry(ItemStack stack, long count, long aur) {
        JsonObject json = itemEntry(stack, count);
        json.addProperty("aur", aur);
        return json;
    }

    private static JsonObject itemEntry(ItemStack stack, long count) {
        JsonObject json = new JsonObject();
        json.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        json.addProperty("count", count);
        return json;
    }

    private static String detail(JsonArray sold, JsonArray bought, int pointsSold, int pointsBought, long aurSpent) {
        JsonObject json = new JsonObject();
        json.add("sold", sold);
        json.add("bought", bought);
        json.addProperty("experience_sold", pointsSold);
        json.addProperty("experience_bought", pointsBought);
        json.addProperty("aur_spent", aurSpent);
        return json.toString();
    }
}
