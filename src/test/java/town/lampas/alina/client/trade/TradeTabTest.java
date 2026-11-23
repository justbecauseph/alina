package town.lampas.alina.client.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import town.lampas.alina.trade.network.ExperienceView;
import town.lampas.alina.trade.network.MarketView;
import town.lampas.alina.trade.network.TradeView;

/** Which tabs a view offers, in what order, and how Tab cycles them. */
class TradeTabTest {

    /** A view whose shop has sell rules exactly when {@code selling} is set. */
    private static TradeView view(boolean selling, Optional<ExperienceView> experience) {
        return view(selling, selling, experience);
    }

    private static TradeView view(boolean selling, boolean sellsItems, Optional<ExperienceView> experience) {
        return new TradeView(0L, selling, sellsItems, 0, 0, List.of(), 0, 0, List.of(), experience, false);
    }

    private static ExperienceView rate(int sellPoints, int sellAur, int buyPoints, int buyAur) {
        return new ExperienceView(0, 0, sellPoints, sellAur, 0, buyPoints, buyAur, 0, Optional.empty());
    }

    @Test
    void theTabsFollowWhatTheViewOffers() {
        assertEquals(List.of(TradeTab.SELL), TradeTab.available(view(true, Optional.empty())));
        assertEquals(List.of(TradeTab.EXPERIENCE),
                TradeTab.available(view(false, Optional.of(rate(10, 5, 0, 0)))));
        assertEquals(List.of(), TradeTab.available(view(false, Optional.empty())));
    }

    /** A market experience panel with the given fees, in percent, and -1 for a direction not traded. */
    private static ExperienceView market(int sellFee, int buyFee) {
        MarketView market = new MarketView(500000, 10000, 500000, 10000, sellFee, buyFee, 10, List.of());
        return new ExperienceView(0, 0, 0, 0, 0, 0, 0, 0, Optional.of(market));
    }

    @Test
    void aShopThatPaysPlayersButHasNoSellRulesShowsNoSellTab() {
        assertEquals(List.of(), TradeTab.available(view(true, false, Optional.empty())));
    }

    @Test
    void anExperiencePanelWithNoRateGivesNoTab() {
        assertEquals(List.of(TradeTab.SELL), TradeTab.available(view(true, Optional.of(rate(0, 0, 0, 0)))));
    }

    @Test
    void aMarketWithASellFeeGivesTheExperienceTab() {
        assertEquals(List.of(TradeTab.EXPERIENCE), TradeTab.available(view(false, Optional.of(market(5, -1)))));
    }

    @Test
    void aMarketWithNoFeeInEitherDirectionGivesNoExperienceTab() {
        assertEquals(List.of(), TradeTab.available(view(false, Optional.of(market(-1, -1)))));
    }

    @Test
    void theBuyTabNeedsOffers() {
        assertEquals(List.of(TradeTab.BUY, TradeTab.SELL, TradeTab.EXPERIENCE), TradeTab.available(true, true, true));
        assertEquals(List.of(TradeTab.SELL, TradeTab.EXPERIENCE), TradeTab.available(false, true, true));
    }

    @Test
    void tabCyclesForwardAndBackAndWraps() {
        List<TradeTab> tabs = TradeTab.available(true, true, true);
        assertEquals(TradeTab.SELL, TradeTab.cycle(tabs, TradeTab.BUY, 1));
        assertEquals(TradeTab.EXPERIENCE, TradeTab.cycle(tabs, TradeTab.BUY, -1));
        assertEquals(TradeTab.BUY, TradeTab.cycle(tabs, TradeTab.EXPERIENCE, 1));
    }
}
