package town.lampas.alina.trade.pricing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The constant-product quotes of the experience pool, worked by hand from the rules in {@link ReserveCurve}: the quotes at
 * the pool {@code (500_000, 10_000)}, the reserves after a trade, the trade caps, the restock, the bounds, and the rule that
 * the product of the reserves never falls.
 */
class ReserveCurveTest {
    private static final ReserveCurve.Reserves POOL = new ReserveCurve.Reserves(500_000, 10_000);

    @Test
    void theSellQuotesFollowTheCurveAndTheFee() {
        assertEquals(OptionalInt.of(51), ReserveCurve.sellPoints(POOL, 1, 0));
        assertEquals(OptionalInt.of(5051), ReserveCurve.sellPoints(POOL, 100, 0));
        // The curve gives 51 points for 1 Aur, and a 5 percent fee takes 53.55 up to 54.
        assertEquals(OptionalInt.of(54), ReserveCurve.sellPoints(POOL, 1, 5));
    }

    @Test
    void theBuyQuotesFollowTheCurveAndTheFee() {
        assertEquals(OptionalInt.of(99), ReserveCurve.buyPoints(POOL, 2, 0));
        // The curve gives 99 points for 2 Aur, and a 5 percent fee takes 94.05 down to 94.
        assertEquals(OptionalInt.of(94), ReserveCurve.buyPoints(POOL, 2, 5));
        assertEquals(OptionalInt.of(4950), ReserveCurve.buyPoints(POOL, 100, 0));
    }

    @Test
    void aSellQuoteIsTheFewestPointsWhosePayoutReachesTheAur() {
        for (int aur : new int[] {1, 7, 100, 999}) {
            long points = ReserveCurve.sellPoints(POOL, aur, 0).getAsInt();

            assertTrue(payout(points) >= aur, "the payout for " + points + " points is under " + aur + " Aur");
            assertTrue(payout(points - 1) < aur, "one point fewer already pays " + aur + " Aur");
        }
    }

    @Test
    void sellingAndBuyingBackWithTheSameAurReturnsFewerPointsThanWereGiven() {
        ReserveCurve.Reserves after = ReserveCurve.afterSell(POOL, 5051, 100).orElseThrow();

        assertEquals(new ReserveCurve.Reserves(505_051, 9_900), after);
        assertEquals(OptionalInt.of(5050), ReserveCurve.buyPoints(after, 100, 0));
    }

    @Test
    void aTradeForAurAtOrAboveThePoolIsEmpty() {
        assertEquals(OptionalInt.empty(), ReserveCurve.sellPoints(POOL, 10_000, 0));
        assertEquals(OptionalInt.empty(), ReserveCurve.sellPoints(POOL, 20_000, 0));
    }

    @Test
    void aTradeForNoAurIsEmpty() {
        assertEquals(OptionalInt.empty(), ReserveCurve.sellPoints(POOL, 0, 0));
        assertEquals(OptionalInt.empty(), ReserveCurve.buyPoints(POOL, 0, 0));
    }

    @Test
    void aPurchaseThatRoundsToNoPointsIsEmpty() {
        assertEquals(OptionalInt.empty(), ReserveCurve.buyPoints(new ReserveCurve.Reserves(1000, 1_000_000), 1, 0));
    }

    @Test
    void maxSellAurIsTheTradePercentOfAurCappedJustBelowThePool() {
        assertEquals(1000, ReserveCurve.maxSellAur(POOL, 10));
        assertEquals(1, ReserveCurve.maxSellAur(new ReserveCurve.Reserves(1000, 2), 50));
        assertEquals(0, ReserveCurve.maxSellAur(new ReserveCurve.Reserves(1000, 1), 50));
    }

    @Test
    void maxBuyAurIsTheLargestPurchaseWhosePointsStayWithinTheTradePercent() {
        // The curve gives 49995 points for 1111 Aur and 50035 for 1112. The cap is 50000 points.
        assertEquals(1111, ReserveCurve.maxBuyAur(POOL, 10));
    }

    @Test
    void restockMovesEachReserveTowardTheSeedByThePercentOfTheGap() {
        assertEquals(new ReserveCurve.Reserves(590_000, 8_200),
                ReserveCurve.restock(new ReserveCurve.Reserves(600_000, 8_000), POOL, 10));
        assertEquals(new ReserveCurve.Reserves(500_000, 10_000),
                ReserveCurve.restock(new ReserveCurve.Reserves(500_001, 9_999), POOL, 10));
    }

    @Test
    void aRestockAtZeroPercentKeepsTheLiveReservesAndAtAHundredReachesTheSeed() {
        ReserveCurve.Reserves live = new ReserveCurve.Reserves(600_000, 8_000);

        assertEquals(live, ReserveCurve.restock(live, POOL, 0));
        assertEquals(POOL, ReserveCurve.restock(live, POOL, 100));
    }

    @Test
    void theRateIsPointsPerAurTimesOneHundred() {
        assertEquals(5000, ReserveCurve.rateHundredths(POOL));
        assertEquals(5100, ReserveCurve.rateHundredths(new ReserveCurve.Reserves(505_000, 9_901)));
    }

    @Test
    void aRandomRunOfTradesNeverLowersThePoolProduct() {
        Random random = new Random(42);
        ReserveCurve.Reserves pool = POOL;
        int executed = 0;
        for (int i = 0; i < 5_000; i++) {
            boolean sell = random.nextBoolean();
            int limit = sell ? ReserveCurve.maxSellAur(pool, 10) : ReserveCurve.maxBuyAur(pool, 10);
            if (limit < 1) {
                continue;
            }
            int aur = 1 + random.nextInt(limit);
            int fee = random.nextInt(51);
            Optional<ReserveCurve.Reserves> after = trade(pool, sell, aur, fee);
            if (after.isEmpty()) {
                continue;
            }

            assertTrue(product(after.get()).compareTo(product(pool)) >= 0,
                    "the trade lowered the product from " + pool + " to " + after.get());
            pool = after.get();
            executed++;
        }

        assertTrue(executed > 0, "no trade executed");
    }

    @Test
    void reservesOutsideOneToMaxReserveAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ReserveCurve.Reserves(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ReserveCurve.Reserves(1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ReserveCurve.Reserves(ReserveCurve.MAX_RESERVE + 1, 1));
    }

    @Test
    void theLargestQuotesDoNotOverflow() {
        // The curve gives about 4.6 billion points, and the fee takes it past Integer.MAX_VALUE, so the quote is empty.
        assertEquals(OptionalInt.empty(), ReserveCurve.sellPoints(
                new ReserveCurve.Reserves(ReserveCurve.MAX_RESERVE - 1, ReserveCurve.MAX_RESERVE), Integer.MAX_VALUE, 50));
        assertDoesNotThrow(() -> ReserveCurve.buyPoints(
                new ReserveCurve.Reserves(ReserveCurve.MAX_RESERVE, 1), Integer.MAX_VALUE, 0));
    }

    @Test
    void aSaleWhoseCurveIsPastTheIntRangeIsEmptyBeforeTheFee() {
        // The curve is 4e9 * 2e9 / (2000000001 - 2000000000) = 8e18 points, past Integer.MAX_VALUE. The fee's product is
        // taken after that check, so it cannot wrap a long into a negative quote, at any fee.
        ReserveCurve.Reserves reserves = new ReserveCurve.Reserves(4_000_000_000L, 2_000_000_001L);
        assertEquals(OptionalInt.empty(), ReserveCurve.sellPoints(reserves, 2_000_000_000, 5));
        assertEquals(OptionalInt.empty(), ReserveCurve.sellPoints(reserves, 2_000_000_000, 0));
    }

    @Test
    void aFeeOutsideZeroToFiftyIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ReserveCurve.sellPoints(POOL, 1, 51));
        assertThrows(IllegalArgumentException.class, () -> ReserveCurve.sellPoints(POOL, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> ReserveCurve.buyPoints(POOL, 1, 51));
        assertThrows(IllegalArgumentException.class, () -> ReserveCurve.buyPoints(POOL, 1, -1));
    }

    @Test
    void aMaxTradePercentOutsideOneToFiftyIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ReserveCurve.maxSellAur(POOL, 0));
        assertThrows(IllegalArgumentException.class, () -> ReserveCurve.maxSellAur(POOL, 51));
        assertThrows(IllegalArgumentException.class, () -> ReserveCurve.maxBuyAur(POOL, 0));
        assertThrows(IllegalArgumentException.class, () -> ReserveCurve.maxBuyAur(POOL, 51));
    }

    @Test
    void aRestockPercentOutsideZeroToOneHundredIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ReserveCurve.restock(POOL, POOL, 101));
        assertThrows(IllegalArgumentException.class, () -> ReserveCurve.restock(POOL, POOL, -1));
    }

    @Test
    void aSaleOrPurchaseOfNoPointsOrAurIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ReserveCurve.afterSell(POOL, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> ReserveCurve.afterBuy(POOL, 1, 0));
    }

    @Test
    void anAfterReserveOutsideOneToMaxReserveIsEmpty() {
        // Taking every point out of the pool leaves no points in it.
        assertEquals(Optional.empty(), ReserveCurve.afterBuy(POOL, 500_000, 1));
        // Paying out all the Aur leaves no Aur in it.
        assertEquals(Optional.empty(), ReserveCurve.afterSell(POOL, 1, 10_000));
    }

    /** The Aur-side payout of {@code points} at the pool {@code (500_000, 10_000)}: {@code floor(A * p / (X + p))}. */
    private static long payout(long points) {
        return Math.floorDiv(10_000L * points, 500_000L + points);
    }

    private static BigInteger product(ReserveCurve.Reserves reserves) {
        return BigInteger.valueOf(reserves.points()).multiply(BigInteger.valueOf(reserves.aur()));
    }

    /** The reserves after a sale or a purchase of {@code aur} Aur at {@code fee}, or empty when either quote is empty. */
    private static Optional<ReserveCurve.Reserves> trade(ReserveCurve.Reserves pool, boolean sell, int aur, int fee) {
        if (sell) {
            OptionalInt points = ReserveCurve.sellPoints(pool, aur, fee);
            return points.isEmpty() ? Optional.empty() : ReserveCurve.afterSell(pool, points.getAsInt(), aur);
        }
        OptionalInt points = ReserveCurve.buyPoints(pool, aur, fee);
        return points.isEmpty() ? Optional.empty() : ReserveCurve.afterBuy(pool, points.getAsInt(), aur);
    }
}
