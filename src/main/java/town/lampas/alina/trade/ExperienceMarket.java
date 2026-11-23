package town.lampas.alina.trade;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import town.lampas.alina.storage.MarketTick;
import town.lampas.alina.trade.network.MarketView;
import town.lampas.alina.trade.pricing.ReserveCurve;
import town.lampas.alina.trade.pricing.ReserveCurve.Reserves;

/**
 * The experience market as the server holds it: the live reserves every experience trade moves, the board day they last
 * restocked, the days closed before that, and the bookkeeping that keeps the reserves in step with storage. {@link
 * TradeService} owns one and uses it on the server thread only. Storage keeps each reserve as the exact sum of the seed and
 * every delta applied to it; the live reserves are that sum clamped into 1 to {@link ReserveCurve#MAX_RESERVE}. The static
 * helpers are the arithmetic of the restock and of the most a player may trade, and take no server, so they are tested alone.
 */
final class ExperienceMarket {
    /** The rates the chart keeps: one for each of the last changes of the pool. */
    static final int HISTORY_RATES = MarketView.MAX_HISTORY;
    /** The most board days one restock catches up, however long the server was stopped. */
    static final int MAX_RESTOCK_DAYS = 60;

    /** A trade's change to the points and Aur reserves. */
    record Move(long points, long aur) {}

    /** The live reserves, empty until the market row has loaded. */
    Optional<Reserves> live = Optional.empty();
    /** The board day the live reserves were last restocked on. Set with {@link #live}. */
    LocalDate restockedDay;
    /**
     * The rate after each of the last changes of the pool, in hundredths of a point per Aur, oldest first, at most
     * {@link #HISTORY_RATES}.
     */
    List<Integer> history = List.of();
    /** True while the market row and its history are being read. */
    boolean loadInFlight;
    /** The tick a failed load may be tried again at. */
    long retryAt;
    /**
     * The market trades this run began whose result storage has not reported. Each move is already in {@link #live}, and is
     * removed when the trade's applied listener runs. Not cleared when a player leaves; only the service's stop drops it.
     */
    final Map<UUID, Move> begun = new HashMap<>();
    /**
     * Counts every change that could make a read of the row differ from the live reserves: a trade begun, a foreign apply, a
     * restock and an adjustment. A reconcile that started at one value of it does not install the row when it has moved.
     */
    long epoch;
    /** True while a restock write is in flight. Its completion clears it through the server thread. */
    boolean restockInFlight;
    /** The staff adjustments in flight. Each completion takes one away. */
    int adjustsInFlight;
    /** Whether the live reserves may differ from the row for a reason a reconcile repairs. */
    boolean reconcileDue;
    /** True while a reconcile's row read is in flight. */
    boolean reconcileInFlight;
    /** The tick a failed reconcile read may be tried again at. */
    long reconcileRetryAt;

    /** The market as a trade or a view reads it: the live reserves, the content's seed and cap, and the rate history. */
    record Snapshot(Reserves live, Reserves seed, int maxTradePercent, List<Integer> history) {}

    /**
     * Installs the row, clamping each reserve into 1 to {@link ReserveCurve#MAX_RESERVE}, and replaces the rate history with
     * the stored ticks, which are newest first. Never throws. Returns whether a reserve was clamped, so the caller can say so.
     */
    boolean install(long points, long aur, LocalDate restockedDay, List<MarketTick> newestFirst) {
        long clampedPoints = inRange(points);
        long clampedAur = inRange(aur);
        this.live = Optional.of(new Reserves(clampedPoints, clampedAur));
        this.restockedDay = restockedDay;
        this.history = ratesOf(newestFirst);
        return clampedPoints != points || clampedAur != aur;
    }

    /** The value kept in 1 to {@link ReserveCurve#MAX_RESERVE}. */
    static long inRange(long value) {
        return Math.clamp(value, 1, ReserveCurve.MAX_RESERVE);
    }

    /** Whether {@code value} is outside 1 to {@link ReserveCurve#MAX_RESERVE}. */
    static boolean outOfRange(long value) {
        return inRange(value) != value;
    }

    /** Whether the move would take a reserve outside its range, so that {@link #moved} changes the sum. */
    static boolean clamps(Reserves live, long points, long aur) {
        return outOfRange(live.points() + points) || outOfRange(live.aur() + aur);
    }

    /** The reserves after a trade's change to each of them, each kept in 1 to {@link ReserveCurve#MAX_RESERVE}. */
    static Reserves moved(Reserves live, long points, long aur) {
        return new Reserves(inRange(live.points() + points), inRange(live.aur() + aur));
    }

    /**
     * The reserves after {@code days} board days of restock, one restock for each day and at most {@link #MAX_RESTOCK_DAYS}.
     * Zero days return {@code live}.
     */
    static Reserves restocked(Reserves live, Reserves seed, int percent, long days) {
        Reserves reserves = live;
        long steps = Math.min(Math.max(0, days), MAX_RESTOCK_DAYS);
        for (long step = 0; step < steps; step++) {
            reserves = ReserveCurve.restock(reserves, seed, percent);
        }
        return reserves;
    }

    /**
     * The rate of each tick in hundredths of a point per Aur, oldest first, from ticks that are newest first. A tick with a
     * reserve outside 1 to {@link ReserveCurve#MAX_RESERVE} has no rate and is skipped.
     */
    static List<Integer> ratesOf(List<MarketTick> newestFirst) {
        List<Integer> rates = new ArrayList<>(newestFirst.size());
        for (int index = newestFirst.size() - 1; index >= 0; index--) {
            MarketTick tick = newestFirst.get(index);
            if (!outOfRange(tick.points()) && !outOfRange(tick.aur())) {
                rates.add(rateOf(new Reserves(tick.points(), tick.aur())));
            }
        }
        return keepLast(rates);
    }

    /** The rate of the reserves in hundredths of a point per Aur, clamped to an int. */
    static int rateOf(Reserves reserves) {
        return (int) Math.min(ReserveCurve.rateHundredths(reserves), Integer.MAX_VALUE);
    }

    /** The history with the rate of one more change added at the end, and the oldest dropped past {@link #HISTORY_RATES}. */
    static List<Integer> withRate(List<Integer> history, int rate) {
        List<Integer> rates = new ArrayList<>(history.size() + 1);
        rates.addAll(history);
        rates.add(rate);
        return keepLast(rates);
    }

    /** The last {@link #HISTORY_RATES} of a history, oldest first. */
    static List<Integer> keepLast(List<Integer> rates) {
        return List.copyOf(rates.subList(Math.max(0, rates.size() - HISTORY_RATES), rates.size()));
    }

    /**
     * The most Aur a sale of experience may pay now: the largest amount whose points, at the sell fee, the player holds, and
     * which is within the budget and {@link ReserveCurve#maxSellAur}. Zero when the shop has no sell fee. The points rise with
     * the amount, so this searches.
     */
    static int maxSellAur(Reserves reserves, OptionalInt sellFee, long held, int budgetLeft, int maxTradePercent) {
        if (sellFee.isEmpty()) {
            return 0;
        }
        int fee = sellFee.getAsInt();
        long low = 0;
        long high = Math.min(budgetLeft, ReserveCurve.maxSellAur(reserves, maxTradePercent));
        while (low < high) {
            long middle = low + (high - low + 1) / 2;
            if (sellPointsFit(reserves, middle, fee, held)) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return (int) low;
    }

    /**
     * The most Aur a purchase of experience may cost now: the largest amount, within the Aur held and
     * {@link ReserveCurve#maxBuyAur}, whose points at the buy fee keep the player's points within an int. Zero when the shop
     * has no buy fee, or when even that amount gives no point. The points rise with the amount, so this searches.
     */
    static int maxBuyAur(Reserves reserves, OptionalInt buyFee, long aurHeld, long held, int maxTradePercent) {
        if (buyFee.isEmpty()) {
            return 0;
        }
        int fee = buyFee.getAsInt();
        long room = TradePlanner.MAX_POINTS - held;
        long low = 0;
        long high = Math.min(aurHeld, ReserveCurve.maxBuyAur(reserves, maxTradePercent));
        while (low < high) {
            long middle = low + (high - low + 1) / 2;
            if (buyPointsOf(reserves, middle, fee) <= room) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return buyPointsOf(reserves, low, fee) >= 1 ? (int) low : 0;
    }

    private static boolean sellPointsFit(Reserves reserves, long aur, int fee, long held) {
        OptionalInt points = ReserveCurve.sellPoints(reserves, (int) aur, fee);
        return points.isPresent() && points.getAsInt() <= held;
    }

    /** The points a purchase of {@code aur} Aur gives at the fee, or 0 when the curve gives none. */
    private static long buyPointsOf(Reserves reserves, long aur, int fee) {
        OptionalInt points = ReserveCurve.buyPoints(reserves, (int) aur, fee);
        return points.isPresent() ? points.getAsInt() : 0;
    }
}
