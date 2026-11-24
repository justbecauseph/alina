package town.lampas.alina.trade.pricing;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * The constant-product curve of the experience pool. The pool holds two reserves, experience points X and Aur A. A trade
 * moves them along X × A, and every quote rounds against the player, so the product of the reserves never falls.
 */
public final class ReserveCurve {
    /** The most either reserve may hold: at this size a reserve times an int still fits a long. */
    public static final long MAX_RESERVE = 4_000_000_000L;
    /** The most percent of the quoted points a market trade may move against the player and still go through. */
    public static final int MAX_QUOTE_TOLERANCE = 10;

    private ReserveCurve() {}

    /**
     * The pool's two reserves.
     *
     * @param points the experience points reserve X
     * @param aur the Aur reserve A
     * @throws IllegalArgumentException when either reserve is outside 1 to {@link #MAX_RESERVE}
     */
    public record Reserves(long points, long aur) {
        /** Refuses a reserve outside 1 to {@link #MAX_RESERVE}. */
        public Reserves {
            requireReserve(points, "points");
            requireReserve(aur, "aur");
        }
    }

    /**
     * The points a player gives to be paid {@code aur} Aur. The quote is the fewest points whose curve payout
     * {@code floor(A * p / (X + p))} reaches {@code aur}, rounded up, plus {@code feePercent} of that, rounded up.
     *
     * @return the points to give, or empty when {@code aur} is below 1, when {@code aur} is not below A, when the points
     *     are over {@link Integer#MAX_VALUE}, or when X plus the points is over {@link #MAX_RESERVE}
     * @throws IllegalArgumentException when {@code feePercent} is outside 0 to 50
     */
    public static OptionalInt sellPoints(Reserves reserves, int aur, int feePercent) {
        requireFee(feePercent);
        if (aur < 1 || aur >= reserves.aur()) {
            return OptionalInt.empty();
        }
        long curve = Math.ceilDiv(reserves.points() * aur, reserves.aur() - aur);
        // Checked before the fee, so the fee's product cannot overflow a long.
        if (curve > Integer.MAX_VALUE) {
            return OptionalInt.empty();
        }
        long withFee = Math.ceilDiv(curve * (100 + feePercent), 100);
        if (withFee > Integer.MAX_VALUE || reserves.points() + withFee > MAX_RESERVE) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) withFee);
    }

    /**
     * The points a player gets for paying {@code aur} Aur. The curve gives {@code floor(X * aur / (A + aur))} points, and the
     * fee takes {@code feePercent} of that, rounded down.
     *
     * @return the points to get, or empty when {@code aur} is below 1, when A plus {@code aur} is over {@link #MAX_RESERVE},
     *     when the points are below 1, or when the points are over {@link Integer#MAX_VALUE}
     * @throws IllegalArgumentException when {@code feePercent} is outside 0 to 50
     */
    public static OptionalInt buyPoints(Reserves reserves, int aur, int feePercent) {
        requireFee(feePercent);
        if (aur < 1 || reserves.aur() + aur > MAX_RESERVE) {
            return OptionalInt.empty();
        }
        long curve = Math.floorDiv(reserves.points() * aur, reserves.aur() + aur);
        long got = Math.floorDiv(curve * (100 - feePercent), 100);
        if (got < 1 || got > Integer.MAX_VALUE) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) got);
    }

    /**
     * The reserves after a sale: X plus {@code points}, and A minus {@code aur}.
     *
     * @return the new reserves, or empty when either new reserve is outside 1 to {@link #MAX_RESERVE}
     * @throws IllegalArgumentException when {@code points} or {@code aur} is below 1
     */
    public static Optional<Reserves> afterSell(Reserves reserves, int points, int aur) {
        requirePositiveAmount(points, "points");
        requirePositiveAmount(aur, "aur");
        return reserveOf(reserves.points() + points, reserves.aur() - aur);
    }

    /**
     * The reserves after a purchase: X minus {@code points}, and A plus {@code aur}.
     *
     * @return the new reserves, or empty when either new reserve is outside 1 to {@link #MAX_RESERVE}
     * @throws IllegalArgumentException when {@code points} or {@code aur} is below 1
     */
    public static Optional<Reserves> afterBuy(Reserves reserves, int points, int aur) {
        requirePositiveAmount(points, "points");
        requirePositiveAmount(aur, "aur");
        return reserveOf(reserves.points() - points, reserves.aur() + aur);
    }

    /**
     * The most Aur one sale may take: {@code maxTradePercent} of A, and no more than A - 1 or {@link Integer#MAX_VALUE}.
     *
     * @throws IllegalArgumentException when {@code maxTradePercent} is outside 1 to 50
     */
    public static int maxSellAur(Reserves reserves, int maxTradePercent) {
        requireTradePercent(maxTradePercent);
        long cap = Math.min(Math.floorDiv(reserves.aur() * maxTradePercent, 100), reserves.aur() - 1);
        return (int) Math.min(cap, Integer.MAX_VALUE);
    }

    /**
     * The most Aur one purchase may pay: the largest {@code a} whose curve points, {@code floor(X * a / (A + a))}, are at most
     * {@code floor(X * maxTradePercent / 100)}. The search runs over 0 up to the smaller of {@link Integer#MAX_VALUE} and
     * {@link #MAX_RESERVE} - A.
     *
     * @throws IllegalArgumentException when {@code maxTradePercent} is outside 1 to 50
     */
    public static int maxBuyAur(Reserves reserves, int maxTradePercent) {
        requireTradePercent(maxTradePercent);
        long limit = Math.floorDiv(reserves.points() * maxTradePercent, 100);
        long low = 0;
        long high = Math.min(Integer.MAX_VALUE, MAX_RESERVE - reserves.aur());
        while (low < high) {
            long middle = low + (high - low + 1) / 2;
            if (Math.floorDiv(reserves.points() * middle, reserves.aur() + middle) <= limit) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return (int) low;
    }

    /**
     * Moves each reserve toward the seed's by {@code percent} of the gap, rounded up. A reserve moves at least 1 when the
     * percent and the gap are both non-zero, and never passes the seed. Percent 0 returns {@code live}; percent 100 returns
     * the seed's reserves.
     *
     * @throws IllegalArgumentException when {@code percent} is outside 0 to 100
     */
    public static Reserves restock(Reserves live, Reserves seed, int percent) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("a restock percent is from 0 to 100, was " + percent);
        }
        return new Reserves(moveToward(live.points(), seed.points(), percent), moveToward(live.aur(), seed.aur(), percent));
    }

    /** The points per Aur, times 100, rounded down: {@code X * 100 / A}. */
    public static long rateHundredths(Reserves reserves) {
        return Math.floorDiv(reserves.points() * 100, reserves.aur());
    }

    /**
     * Whether a market trade goes through when the points the server works out now differ from the points the client quoted.
     * With {@code tolerancePercent} 0 only an equal figure does. Above 0, a quote below 1 does not, a figure in the player's
     * favour always does, and a figure against the player does when it is at most {@code ceil(quoted * tolerancePercent / 100)}
     * points away from the quote. The trade settles at the live figure, so this decides only whether it is refused as stale.
     *
     * @param selling true when the player gives points, false when the player gets them
     * @param quoted the points the client quoted
     * @param live the points the server works out now
     * @param tolerancePercent how far, in percent of the quoted points, a figure may move against the player, from 0 to
     *     {@link #MAX_QUOTE_TOLERANCE}
     * @throws IllegalArgumentException when {@code tolerancePercent} is outside 0 to {@link #MAX_QUOTE_TOLERANCE}
     */
    public static boolean acceptsQuote(boolean selling, int quoted, int live, int tolerancePercent) {
        requireQuoteTolerance(tolerancePercent);
        if (tolerancePercent == 0) {
            return live == quoted;
        }
        if (quoted < 1) {
            return false;
        }
        if (selling ? live <= quoted : live >= quoted) {
            return true;
        }
        long againstPlayer = selling ? (long) live - quoted : (long) quoted - live;
        return againstPlayer <= Math.ceilDiv((long) quoted * tolerancePercent, 100);
    }

    private static void requireQuoteTolerance(int tolerancePercent) {
        if (tolerancePercent < 0 || tolerancePercent > MAX_QUOTE_TOLERANCE) {
            throw new IllegalArgumentException(
                    "a quote tolerance percent is from 0 to " + MAX_QUOTE_TOLERANCE + ", was " + tolerancePercent);
        }
    }

    private static void requireReserve(long value, String name) {
        if (value < 1 || value > MAX_RESERVE) {
            throw new IllegalArgumentException(name + " is a reserve from 1 to " + MAX_RESERVE + ", was " + value);
        }
    }

    private static void requireFee(int feePercent) {
        if (feePercent < 0 || feePercent > 50) {
            throw new IllegalArgumentException("a fee percent is from 0 to 50, was " + feePercent);
        }
    }

    private static void requireTradePercent(int maxTradePercent) {
        if (maxTradePercent < 1 || maxTradePercent > 50) {
            throw new IllegalArgumentException("a max trade percent is from 1 to 50, was " + maxTradePercent);
        }
    }

    private static void requirePositiveAmount(int amount, String name) {
        if (amount < 1) {
            throw new IllegalArgumentException(name + " is at least 1, was " + amount);
        }
    }

    /** The reserves for the two values, or empty when either is outside 1 to {@link #MAX_RESERVE}. */
    private static Optional<Reserves> reserveOf(long points, long aur) {
        if (points < 1 || points > MAX_RESERVE || aur < 1 || aur > MAX_RESERVE) {
            return Optional.empty();
        }
        return Optional.of(new Reserves(points, aur));
    }

    /** The live reserve moved toward the seed's by {@code percent} of the gap, rounded up, and never past the seed. */
    private static long moveToward(long live, long seed, int percent) {
        long gap = seed - live;
        long move = Math.ceilDiv(Math.abs(gap) * percent, 100);
        return gap < 0 ? live - move : live + move;
    }
}
