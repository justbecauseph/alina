package town.lampas.alina.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import town.lampas.alina.content.TradeContent.Denomination;
import town.lampas.alina.content.TradeContent.ExperienceFees;
import town.lampas.alina.content.TradeContent.ExperienceRates;
import town.lampas.alina.content.TradeContent.Offer;
import town.lampas.alina.content.TradeContent.Rate;
import town.lampas.alina.content.TradeContent.Selling;
import town.lampas.alina.content.TradeContent.Shop;
import town.lampas.alina.trade.network.ExperienceView;
import town.lampas.alina.trade.network.MarketView;
import town.lampas.alina.trade.network.OfferRow;
import town.lampas.alina.trade.network.SellRow;
import town.lampas.alina.trade.network.TradeView;
import town.lampas.alina.trade.pricing.SellPlanner;

/** Builds the trade screen's view of one player at one shop, and tells two views apart. */
final class TradeViews {

    private TradeViews() {}

    /**
     * The view of the shop for the player. {@code aur} is the Aur the player holds, {@code budgetLeft} what the shop can
     * still pay them today (0 for a shop that does not buy), {@code points} and {@code level} the player's experience.
     * {@code market} is the experience market, empty while it has not loaded or the content has none.
     */
    static TradeView build(Shop shop, List<Denomination> currency, Inventory inventory, long aur, int budgetLeft,
            int points, int level, List<OfferState> offers, boolean busy, Optional<ExperienceMarket.Snapshot> market) {
        int main = inventory.getNonEquipmentItems().size();
        List<SellRow> rows = new ArrayList<>();
        for (int slot = 0; slot < main && rows.size() < TradeView.MAX_SELL_ROWS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            Optional<SellPlanner.Quote> quote = SellPlanner.quote(shop, currency, stack);
            if (quote.isEmpty()) {
                continue;
            }
            int per = quote.get().per();
            int price = quote.get().aur();
            int bundles = Math.min(stack.getCount() / per, budgetLeft / price);
            rows.add(new SellRow(slot, stack, price, per, bundles * per, quote.get().rule().junk()));
        }
        SellPlanner.JunkPlan junk = SellPlanner.junk(shop, currency, inventory, budgetLeft);
        List<OfferRow> offerRows = new ArrayList<>();
        for (OfferState state : offers) {
            if (offerRows.size() >= TradeView.MAX_OFFERS) {
                break;
            }
            offerRows.add(offerRow(state, aur));
        }
        Optional<ExperienceView> experience = shop.experience()
                .map(rates -> experienceView(rates, points, level, aur, budgetLeft))
                .or(() -> shop.experienceFees().flatMap(fees -> market.map(
                        snapshot -> marketExperienceView(fees, snapshot, points, level, aur, budgetLeft))));
        boolean sellsItems = shop.selling().map(s -> !s.rules().isEmpty()).orElse(false);
        return new TradeView(aur, shop.selling().isPresent(), sellsItems, budgetLeft,
                shop.selling().map(Selling::dailyBudget).orElse(0), rows, junk.items(), junk.aur(), offerRows,
                experience, busy);
    }

    /** Whether two views show the same thing. Stacks are compared with {@link ItemStack#matches}, not by identity. */
    static boolean same(TradeView a, TradeView b) {
        return a.aur() == b.aur()
                && a.selling() == b.selling()
                && a.sellsItems() == b.sellsItems()
                && a.budgetLeft() == b.budgetLeft()
                && a.dailyBudget() == b.dailyBudget()
                && a.junkItems() == b.junkItems()
                && a.junkAur() == b.junkAur()
                && a.busy() == b.busy()
                && a.experience().equals(b.experience())
                && sameRows(a.sellRows(), b.sellRows())
                && sameOffers(a.offers(), b.offers());
    }

    private static OfferRow offerRow(OfferState state, long aur) {
        Offer offer = state.offer();
        int maxCount = 0;
        if (state.locked().isEmpty()) {
            long affordable = Math.min(TradePlanner.MAX_PURCHASES, aur / offer.price());
            if (state.limitLeft() >= 0) {
                affordable = Math.min(affordable, state.limitLeft());
            }
            maxCount = (int) affordable;
        }
        Optional<Component> locked = state.locked();
        return new OfferRow(offer.id(), offer.item(), offer.price(), state.limitLeft(), maxCount, locked);
    }

    /**
     * The experience panel, with the rates unchanged. The most the player can sell is what their points cover, capped by the
     * budget; the most they can buy is the Aur they hold, capped so their points stay within an int. Both are multiples of
     * the rate's Aur, rounded down, since a request must be one.
     */
    private static ExperienceView experienceView(ExperienceRates rates, int points, int level, long aur, int budgetLeft) {
        int sellPoints = rates.sell().map(Rate::points).orElse(0);
        int sellAur = rates.sell().map(Rate::aur).orElse(0);
        long maxSell = rates.sell().map(rate -> maxSellAur(points, budgetLeft, rate)).orElse(0L);
        int buyPoints = rates.buy().map(Rate::points).orElse(0);
        int buyAur = rates.buy().map(Rate::aur).orElse(0);
        long maxBuy = rates.buy().map(rate -> maxBuyAur(aur, points, rate)).orElse(0L);
        return new ExperienceView(points, level, sellPoints, sellAur, (int) maxSell, buyPoints, buyAur, (int) maxBuy,
                Optional.empty());
    }

    /**
     * The experience panel of a shop that trades at the market. It has no fixed rates, so the points and Aur of its rates are
     * 0; the most the player can sell or buy is the curve's, and the market itself is {@link MarketView}.
     */
    private static ExperienceView marketExperienceView(ExperienceFees fees, ExperienceMarket.Snapshot market, int points,
            int level, long aur, int budgetLeft) {
        long maxSell = ExperienceMarket.maxSellAur(market.live(), fees.sell(), points, budgetLeft, market.maxTradePercent());
        long maxBuy = ExperienceMarket.maxBuyAur(market.live(), fees.buy(), aur, points, market.maxTradePercent());
        MarketView view = new MarketView(market.live().points(), market.live().aur(), market.seed().points(),
                market.seed().aur(), fees.sell().orElse(-1), fees.buy().orElse(-1), market.maxTradePercent(),
                market.quoteTolerancePercent(), market.history());
        return new ExperienceView(points, level, 0, 0, (int) maxSell, 0, 0, (int) maxBuy, Optional.of(view));
    }

    /** The most Aur a sale of experience can pay: the whole rate steps the points cover, capped by the budget. */
    private static long maxSellAur(int points, int budgetLeft, Rate rate) {
        long fromPoints = (long) (points / rate.points()) * rate.aur();
        return roundDown(Math.min(budgetLeft, fromPoints), rate.aur());
    }

    /** The most Aur a purchase of experience can cost: the Aur held, capped so the points it gives stay within an int. */
    private static long maxBuyAur(long aurHeld, int points, Rate rate) {
        long room = TradePlanner.MAX_POINTS - points;
        long fromRoom = (room / rate.points()) * rate.aur();
        return roundDown(Math.min(Math.min(aurHeld, fromRoom), Integer.MAX_VALUE), rate.aur());
    }

    private static long roundDown(long value, int multiple) {
        return value - value % multiple;
    }

    private static boolean sameRows(List<SellRow> a, List<SellRow> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            SellRow x = a.get(i);
            SellRow y = b.get(i);
            if (x.slot() != y.slot() || x.aur() != y.aur() || x.per() != y.per() || x.maxCount() != y.maxCount()
                    || x.junk() != y.junk() || !ItemStack.matches(x.stack(), y.stack())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameOffers(List<OfferRow> a, List<OfferRow> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            OfferRow x = a.get(i);
            OfferRow y = b.get(i);
            if (!x.id().equals(y.id()) || x.price() != y.price() || x.limitLeft() != y.limitLeft()
                    || x.maxCount() != y.maxCount() || !x.locked().equals(y.locked())
                    || !ItemStack.matches(x.stack(), y.stack())) {
                return false;
            }
        }
        return true;
    }
}
