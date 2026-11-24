package town.lampas.alina.client.trade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import town.lampas.alina.trade.network.ExperienceView;
import town.lampas.alina.trade.network.MarketView;

/** The Aur and experience figures the screen shows and sends. */
class ExchangeTest {

    private static ExperienceView rates(int sellPoints, int sellAur, int buyPoints, int buyAur) {
        return new ExperienceView(0, 0, sellPoints, sellAur, 0, buyPoints, buyAur, 0, Optional.empty());
    }

    /** A market over reserves of 500000 points and 10000 Aur, seeded the same, capped at 10 percent per trade. */
    private static ExperienceView market(int sellFee, int buyFee) {
        MarketView market = new MarketView(500000, 10000, 500000, 10000, sellFee, buyFee, 10, 0, List.of());
        return new ExperienceView(0, 0, 0, 0, 0, 0, 0, 0, Optional.of(market));
    }

    @Test
    void aSalePaysWholeRoundsOfPer() {
        assertEquals(24, Exchange.payout(6, 3, 12));
        assertEquals(40, Exchange.payout(1, 1, 40));
        assertEquals(0, Exchange.payout(0, 3, 12));
    }

    @Test
    void aSaleWithNoPerPaysNothing() {
        assertEquals(0, Exchange.payout(4, 0, 12));
    }

    @Test
    void aPurchaseCostsQuantityTimesPrice() {
        assertEquals(120, Exchange.cost(3, 40));
        assertEquals(Integer.MAX_VALUE, Exchange.cost(Integer.MAX_VALUE, 2));
    }

    @Test
    void experiencePointsAreWholeRoundsOfTheRate() {
        assertEquals(35, Exchange.experiencePoints(5, 1, 7));
        assertEquals(14, Exchange.experiencePoints(4, 2, 7));
        assertEquals(14, Exchange.experiencePoints(5, 2, 7));
        assertEquals(0, Exchange.experiencePoints(5, 0, 7));
    }

    @Test
    void aRateOffersAPathOnlyWhenItHasPointsAndAur() {
        assertTrue(Exchange.experienceSellOffered(rates(10, 5, 0, 0)));
        assertFalse(Exchange.experienceSellOffered(rates(0, 5, 10, 5)));
        assertFalse(Exchange.experienceSellOffered(rates(10, 0, 0, 0)));
        assertTrue(Exchange.experienceBuyOffered(rates(0, 0, 10, 5)));
        assertFalse(Exchange.experienceBuyOffered(rates(10, 5, 0, 5)));
    }

    @Test
    void theBarFillsInProportionToThePointsDone() {
        assertEquals(0, Exchange.barFill(100, 0, 7));
        assertEquals(100, Exchange.barFill(100, 7, 7));
        assertEquals(61, Exchange.barFill(100, 231, 373));
    }

    @Test
    void theBarStaysBetweenEmptyAndFull() {
        assertEquals(100, Exchange.barFill(100, 500, 373));
        assertEquals(0, Exchange.barFill(100, -5, 373));
    }

    @Test
    void aBarWithNoWidthOrNoPointsNeededIsEmpty() {
        assertEquals(0, Exchange.barFill(0, 1, 2));
        assertEquals(0, Exchange.barFill(100, 1, 0));
    }

    @Test
    void theBarDoesNotOverflowAtALargeWidth() {
        assertEquals(1073741823, Exchange.barFill(Integer.MAX_VALUE, 1, 2));
    }

    @Test
    void aMarketQuotesItsPointsFromTheCurveAtItsFees() {
        assertEquals(54, Exchange.experienceSellPoints(market(5, 5), 1));
        assertEquals(94, Exchange.experienceBuyPoints(market(5, 5), 2));
    }

    @Test
    void aMarketWithNoFeesQuotesTheCurveWithoutAFee() {
        assertEquals(5051, Exchange.experienceSellPoints(market(0, 0), 100));
        assertEquals(4950, Exchange.experienceBuyPoints(market(0, 0), 100));
    }

    @Test
    void aMarketQuotesNothingForAnAmountBelowOneAur() {
        assertEquals(0, Exchange.experienceSellPoints(market(5, 5), 0));
        assertEquals(0, Exchange.experienceBuyPoints(market(5, 5), 0));
    }

    @Test
    void aMarketDirectionWithNoFeeIsNotOfferedAndQuotesNothing() {
        ExperienceView noSell = market(-1, 5);
        assertFalse(Exchange.experienceSellOffered(noSell));
        assertTrue(Exchange.experienceBuyOffered(noSell));
        assertEquals(0, Exchange.experienceSellPoints(noSell, 1));

        ExperienceView noBuy = market(5, -1);
        assertTrue(Exchange.experienceSellOffered(noBuy));
        assertFalse(Exchange.experienceBuyOffered(noBuy));
        assertEquals(0, Exchange.experienceBuyPoints(noBuy, 1));
    }

    @Test
    void aMarketWithNoFeeInEitherDirectionOffersNoExperience() {
        ExperienceView none = market(-1, -1);
        assertFalse(Exchange.experienceSellOffered(none));
        assertFalse(Exchange.experienceBuyOffered(none));
    }

    @Test
    void aFixedRateQuotesItsRateAndStepsByItsAur() {
        ExperienceView fixed = rates(50, 1, 50, 2);
        assertTrue(Exchange.experienceSellOffered(fixed));
        assertTrue(Exchange.experienceBuyOffered(fixed));
        assertEquals(150, Exchange.experienceSellPoints(fixed, 3));
        assertEquals(100, Exchange.experienceBuyPoints(fixed, 4));
        assertEquals(1, Exchange.experienceStep(fixed, true));
        assertEquals(2, Exchange.experienceStep(fixed, false));
    }

    @Test
    void aMarketStepsOneAurAtATime() {
        assertEquals(1, Exchange.experienceStep(market(5, 5), true));
        assertEquals(1, Exchange.experienceStep(market(5, 5), false));
    }

    @Test
    void theMarketRateIsInHundredthsOfAPoint() {
        MarketView live = new MarketView(505000, 9901, 500000, 10000, 5, 5, 10, 0, List.of());
        assertEquals(5100, Exchange.marketRate(live));
    }

    @Test
    void thePriceOfAHundredPointsIsInHundredthsOfAnAur() {
        assertEquals(200, Exchange.priceHundredths(500000, 10000));
        assertEquals(196, Exchange.priceHundredths(505051, 9900));
        assertEquals(400, Exchange.priceHundredths(250000, 10000));
    }

    @Test
    void aPriceWithAnEmptyReserveIsZero() {
        assertEquals(0, Exchange.priceHundredths(0, 10000));
        assertEquals(0, Exchange.priceHundredths(500000, 0));
    }

    @Test
    void aRateGivesThePriceOfAHundredPoints() {
        assertEquals(200, Exchange.priceOfRate(5000));
        assertEquals(196, Exchange.priceOfRate(5101));
        assertEquals(400, Exchange.priceOfRate(2500));
        assertEquals(0, Exchange.priceOfRate(0));
    }

    @Test
    void theChartIsTheHistorysPricesThenTheLivePrice() {
        assertEquals(List.of(200), Exchange.chartValues(List.of(), 5000));
        assertEquals(List.of(200, 196), Exchange.chartValues(List.of(5000, 5101), 5101));
        assertEquals(List.of(200, 196), Exchange.chartValues(List.of(5000), 5101));
    }

    @Test
    void aHistoryRateThatIsNotPositiveIsLeftOutOfTheChart() {
        assertEquals(List.of(200), Exchange.chartValues(List.of(0, 5000), 5000));
        assertEquals(List.of(200, 196), Exchange.chartValues(List.of(-4, 5000), 5101));
    }

    @Test
    void aNonPositiveLivePriceIsLeftOutOfTheChart() {
        assertEquals(List.of(), Exchange.chartValues(List.of(), 0));
    }

    @Test
    void aChartStretchesItsValuesFromTheBottomRowToTheTopRow() {
        assertArrayEquals(new int[] {10, 5, 0}, Exchange.chartYs(List.of(100, 200), 100, 3, 11));
    }

    @Test
    void aChartOfEqualValuesDrawsTheMiddleRow() {
        assertArrayEquals(new int[] {5, 5, 5, 5}, Exchange.chartYs(List.of(200, 200), 200, 4, 11));
    }

    @Test
    void aSingleValueFillsEveryColumnAtItsRow() {
        assertArrayEquals(new int[] {0, 0, 0}, Exchange.chartYs(List.of(200), 100, 3, 11));
    }

    @Test
    void aColumnBetweenTwoValuesIsInterpolatedBetweenThem() {
        assertArrayEquals(new int[] {10, 5, 0, 5, 10}, Exchange.chartYs(List.of(100, 200, 100), 100, 5, 11));
    }

    @Test
    void aColumnHalfwayBetweenTwoRowsRoundsUp() {
        // Columns at 100, 100.5, 101, 101.5 and 102: 0, 2.5, 5, 7.5 and 10 rows below the bottom, halves up.
        assertArrayEquals(new int[] {10, 7, 5, 2, 0}, Exchange.chartYs(List.of(100, 102), 100, 5, 11));
    }

    @Test
    void theOneColumnChartShowsTheLastValue() {
        assertArrayEquals(new int[] {0}, Exchange.chartYs(List.of(150), 100, 1, 11));
    }

    @Test
    void theChartScaleIncludesTheUsualPrice() {
        assertArrayEquals(new int[] {5, 0}, Exchange.chartYs(List.of(150, 200), 100, 2, 11));
        assertEquals(10, Exchange.chartBaseY(List.of(150, 200), 100, 11));
    }

    @Test
    void noValuesOrNoColumnsGiveNoRows() {
        assertArrayEquals(new int[0], Exchange.chartYs(List.of(), 100, 3, 11));
        assertArrayEquals(new int[0], Exchange.chartYs(List.of(100), 100, 0, 11));
    }

    @Test
    void theUsualPriceIsAtItsRowOnTheSameScale() {
        assertEquals(10, Exchange.chartBaseY(List.of(100, 200), 100, 11));
        assertEquals(5, Exchange.chartBaseY(List.of(100, 200), 150, 11));
        assertEquals(5, Exchange.chartBaseY(List.of(200, 200), 200, 11));
    }

    @Test
    void theChangeAgainstTheUsualRateIsRoundedToTheNearestPercent() {
        assertEquals(2, Exchange.changePercent(5100, 5000));
        assertEquals(-2, Exchange.changePercent(4900, 5000));
        assertEquals(0, Exchange.changePercent(5024, 5000));
        assertEquals(1, Exchange.changePercent(5025, 5000));
        assertEquals(-1, Exchange.changePercent(4975, 5000));
    }

    @Test
    void aChangeAgainstANonPositiveUsualRateIsZero() {
        assertEquals(0, Exchange.changePercent(5000, 0));
    }

    @Test
    void aRateInHundredthsReadsWithTwoDecimals() {
        assertEquals("51.00", Exchange.hundredths(5100));
        assertEquals("50.07", Exchange.hundredths(5007));
        assertEquals("0.07", Exchange.hundredths(7));
    }

    @Test
    void aMarketWithAReserveOutOfRangeQuotesNothing() {
        MarketView empty = new MarketView(0, 10000, 500000, 10000, 5, 5, 10, 0, List.of());
        ExperienceView view = new ExperienceView(0, 0, 0, 0, 0, 0, 0, 0, Optional.of(empty));
        assertEquals(0, Exchange.experienceSellPoints(view, 1));
        assertEquals(0, Exchange.marketRate(empty));
    }
}
