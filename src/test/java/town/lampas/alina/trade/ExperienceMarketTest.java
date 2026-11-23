package town.lampas.alina.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import town.lampas.alina.storage.MarketTick;
import town.lampas.alina.trade.pricing.ReserveCurve;
import town.lampas.alina.trade.pricing.ReserveCurve.Reserves;

/**
 * The market's arithmetic without a server. The expected figures are worked from {@link ReserveCurve}'s rules: a restock
 * moves each reserve toward the seed by the percent of the gap, rounded up; a sale of experience pays the points the curve
 * gives, rounded up; a purchase of Aur gives the points the curve gives, rounded down.
 */
class ExperienceMarketTest {
    private static final Reserves SEED = new Reserves(500_000, 10_000);
    /** The pool after a sale of 100 Aur from the seed, at no fee: 5051 points taken as the curve gives them. */
    private static final Reserves AFTER_SALE = new Reserves(505_051, 9_900);
    private static final LocalDate DAY = LocalDate.parse("2026-11-05");
    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");
    private static final int RESTOCK_PERCENT = 10;
    private static final int MAX_TRADE_PERCENT = 10;

    @Test
    void oneDayOfRestockMovesEachReserveTenPercentOfTheGapRoundedUp() {
        // Points: the gap is 5051 down, so 506 come back (505.1 rounded up). Aur: the gap is 100 up, so 10 go out.
        assertEquals(new Reserves(504_545, 9_910),
                ExperienceMarket.restocked(AFTER_SALE, SEED, RESTOCK_PERCENT, 1));
    }

    @Test
    void threeDaysOfRestockRepeatTheDailyMove() {
        // Day 2 from (504545, 9910): points gap 4545 gives 455, aur gap 90 gives 9. Day 3 from (504090, 9919): 409 and 9.
        assertEquals(new Reserves(503_681, 9_928),
                ExperienceMarket.restocked(AFTER_SALE, SEED, RESTOCK_PERCENT, 3));
    }

    @Test
    void noElapsedDayLeavesTheReservesAsTheyAre() {
        assertEquals(AFTER_SALE, ExperienceMarket.restocked(AFTER_SALE, SEED, RESTOCK_PERCENT, 0));
    }

    @Test
    void aZeroPercentRestockNeverMoves() {
        assertEquals(AFTER_SALE, ExperienceMarket.restocked(AFTER_SALE, SEED, 0, 1));
        assertEquals(AFTER_SALE, ExperienceMarket.restocked(AFTER_SALE, SEED, 0, 365));
    }

    @Test
    void theRestockStopsAtSixtyDays() {
        Reserves sixty = restockedByLoop(AFTER_SALE, 60);
        assertEquals(sixty, ExperienceMarket.restocked(AFTER_SALE, SEED, RESTOCK_PERCENT, 60));
        assertEquals(sixty, ExperienceMarket.restocked(AFTER_SALE, SEED, RESTOCK_PERCENT, 61));
        assertEquals(sixty, ExperienceMarket.restocked(AFTER_SALE, SEED, RESTOCK_PERCENT, 1_000));
        // The cap is not vacuous: the pool has not reached the seed by day 60, so a 59th day would move it.
        assertNotEquals(sixty, restockedByLoop(AFTER_SALE, 59));
    }

    @Test
    void aSaleMovesThePointsInAndTheAurOut() {
        // A sale of 100 Aur at no fee takes 5051 points from the player into the pool, and 100 Aur out of it.
        assertEquals(AFTER_SALE, ExperienceMarket.moved(SEED, 5_051, -100));
    }

    @Test
    void aMovedReserveIsKeptFromOneToTheCap() {
        assertEquals(new Reserves(1, 1), ExperienceMarket.moved(new Reserves(5, 5), -50, -50));
        assertEquals(new Reserves(ReserveCurve.MAX_RESERVE, ReserveCurve.MAX_RESERVE),
                ExperienceMarket.moved(new Reserves(ReserveCurve.MAX_RESERVE, ReserveCurve.MAX_RESERVE), 10, 10));
    }

    @Test
    void aPlayerWithFiftyOnePointsSellsAtMostOneAur() {
        // One Aur costs 51 points at this pool; two cost 101.
        assertEquals(1, ExperienceMarket.maxSellAur(SEED, OptionalInt.of(0), 51, 200, MAX_TRADE_PERCENT));
    }

    @Test
    void aPlayerWithFiveThousandFiftyOnePointsSellsAtMostOneHundredAur() {
        // 100 Aur costs 5051 points, and 101 Aur costs 5102.
        assertEquals(100, ExperienceMarket.maxSellAur(SEED, OptionalInt.of(0), 5_051, 200, MAX_TRADE_PERCENT));
    }

    @Test
    void aRichPlayerSellsUpToTheBudget() {
        assertEquals(200, ExperienceMarket.maxSellAur(SEED, OptionalInt.of(0), 1_000_000, 200, MAX_TRADE_PERCENT));
    }

    @Test
    void theCapLimitsTheSaleWhenTheBudgetAllows() {
        // The cap is 10 percent of 10000 Aur: 1000.
        assertEquals(1_000, ExperienceMarket.maxSellAur(SEED, OptionalInt.of(0), 1_000_000, 5_000, MAX_TRADE_PERCENT));
    }

    @Test
    void aShopWithNoSellFeeHasNoSaleToOffer() {
        assertEquals(0, ExperienceMarket.maxSellAur(SEED, OptionalInt.empty(), 1_000_000, 200, MAX_TRADE_PERCENT));
    }

    @Test
    void aBuyerWithThreeAurBuysThreeAur() {
        assertEquals(3, ExperienceMarket.maxBuyAur(SEED, OptionalInt.of(0), 3, 0, MAX_TRADE_PERCENT));
    }

    @Test
    void aBuyerWithFiveThousandAurBuysUpToTheCap() {
        // The cap is the Aur at which 500000 * a / (10000 + a) first passes 50000, which is 1111 (1112 passes it).
        assertEquals(1_111, ExperienceMarket.maxBuyAur(SEED, OptionalInt.of(0), 5_000, 0, MAX_TRADE_PERCENT));
    }

    @Test
    void aBuyerWithNoHeldAurBuysNothing() {
        assertEquals(0, ExperienceMarket.maxBuyAur(SEED, OptionalInt.of(0), 0, 0, MAX_TRADE_PERCENT));
    }

    @Test
    void aBuyerWhoseRoomIsOnePointBuysNothing() {
        // Three Aur at this pool give 149 points, and 1 Aur gives 49, so with one point of room not even 1 Aur fits.
        assertEquals(0, ExperienceMarket.maxBuyAur(SEED, OptionalInt.of(0), 3, TradePlanner.MAX_POINTS - 1,
                MAX_TRADE_PERCENT));
    }

    @Test
    void theRatesOfTheTicksAreOldestFirstInHundredthsOfAPoint() {
        // The seed is 50.00 points per Aur, and the pool after the sale is 51.01 (505051 * 100 / 9900 rounds down to 5101).
        List<MarketTick> newestFirst = List.of(
                new MarketTick(AFTER_SALE.points(), AFTER_SALE.aur(), T0),
                new MarketTick(SEED.points(), SEED.aur(), T0));

        assertEquals(List.of(5_000, 5_101), ExperienceMarket.ratesOf(newestFirst));
    }

    @Test
    void aRingKeepsTheNewest32RatesOldestFirst() {
        List<Integer> history = List.of();
        for (int rate = 1; rate <= 33; rate++) {
            history = ExperienceMarket.withRate(history, rate);
        }

        assertEquals(List.of(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26,
                27, 28, 29, 30, 31, 32, 33), history);
    }

    @Test
    void anInstalledRowReplacesTheRatesWithItsTicks() {
        ExperienceMarket market = new ExperienceMarket();
        market.history = List.of(1, 2, 3);

        market.install(AFTER_SALE.points(), AFTER_SALE.aur(), DAY,
                List.of(new MarketTick(AFTER_SALE.points(), AFTER_SALE.aur(), T0),
                        new MarketTick(SEED.points(), SEED.aur(), T0)));

        assertEquals(List.of(5_000, 5_101), market.history);
    }


    @Test
    void anInstalledRowIsClampedIntoRangeAndTheClampIsReported() {
        ExperienceMarket market = new ExperienceMarket();

        assertFalse(market.install(500_000, 10_000, DAY, List.of()));
        assertTrue(market.install(0, 5_000_000_000L, DAY, List.of()));

        assertEquals(Optional.of(new Reserves(1, ReserveCurve.MAX_RESERVE)), market.live);
    }

    @Test
    void aTickOutsideTheRangeHasNoRate() {
        List<MarketTick> newestFirst = List.of(
                new MarketTick(5_000_000_000L, 10_000, T0),
                new MarketTick(SEED.points(), SEED.aur(), T0));

        assertEquals(List.of(5_000), ExperienceMarket.ratesOf(newestFirst));
    }

    @Test
    void aMoveThatLeavesARangeIsReportedAsAClamp() {
        assertFalse(ExperienceMarket.clamps(SEED, 5_051, -100));
        // The Aur would go to 0 from 500000 - 500000 = 0, below 1.
        assertTrue(ExperienceMarket.clamps(SEED, -500_000, 0));
        assertTrue(ExperienceMarket.clamps(new Reserves(ReserveCurve.MAX_RESERVE, 10), 1, 0));
    }

    /** The reserves after {@code days} single-day restocks, applied one at a time: the cap's reference. */
    private static Reserves restockedByLoop(Reserves live, int days) {
        Reserves reserves = live;
        for (int day = 0; day < days; day++) {
            reserves = ReserveCurve.restock(reserves, SEED, RESTOCK_PERCENT);
        }
        return reserves;
    }
}
