package town.lampas.alina.client.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import town.lampas.alina.trade.network.ExperienceView;
import town.lampas.alina.trade.network.MarketView;
import town.lampas.alina.trade.pricing.ReserveCurve;
import town.lampas.alina.trade.pricing.ReserveCurve.Reserves;

/**
 * The Aur and experience amounts the trade screen shows and sends. Each figure is what the server re-checks: the screen
 * sends the figure it showed, and the server refuses the request when its own figure differs. A shop either has fixed rates,
 * or trades against the experience market, in which case the points come from {@link ReserveCurve}. Pure arithmetic, so it
 * is unit tested.
 */
final class Exchange {
    private Exchange() {}

    /** The Aur a sale pays: {@code quantity / per} whole rounds of {@code aur}. 0 when {@code per} is not positive. */
    static int payout(int quantity, int per, int aur) {
        if (per <= 0) {
            return 0;
        }
        return saturate((long) (quantity / per) * aur);
    }

    /** The Aur a purchase costs: {@code quantity} times the price of one. */
    static int cost(int quantity, int price) {
        return saturate((long) quantity * price);
    }

    /**
     * The experience points that {@code aur} Aur is worth at a rate of {@code ratePoints} points for {@code rateAur} Aur:
     * {@code aur / rateAur} whole rounds of {@code ratePoints}, as the server computes it. The server only takes an Aur
     * amount that is a multiple of {@code rateAur}. 0 when the rate has no Aur.
     */
    static int experiencePoints(int aur, int rateAur, int ratePoints) {
        if (rateAur <= 0) {
            return 0;
        }
        return saturate((long) (aur / rateAur) * ratePoints);
    }

    /**
     * The filled width of a bar {@code width} wide when {@code into} of {@code needed} is done: 0 when width or needed is not
     * positive, never above {@code width}.
     */
    static int barFill(int width, int into, int needed) {
        if (width <= 0 || needed <= 0) {
            return 0;
        }
        int done = Math.clamp(into, 0, needed);
        return (int) ((long) width * done / needed);
    }

    /**
     * Whether the shop pays for experience: its fixed sell rate has points and Aur, or it trades against the market and
     * takes a sell fee.
     */
    static boolean experienceSellOffered(ExperienceView experience) {
        boolean fixed = experience.sellPoints() > 0 && experience.sellAur() > 0;
        return fixed || experience.market().map(market -> market.sellFee() >= 0).orElse(false);
    }

    /**
     * Whether the shop sells experience: its fixed buy rate has points and Aur, or it trades against the market and takes a
     * buy fee.
     */
    static boolean experienceBuyOffered(ExperienceView experience) {
        boolean fixed = experience.buyPoints() > 0 && experience.buyAur() > 0;
        return fixed || experience.market().map(market -> market.buyFee() >= 0).orElse(false);
    }

    /**
     * The points a player gives to be paid {@code aur} Aur. A shop with a market quotes the curve at its sell fee, and
     * gives 0 when the quote is empty; a fixed-rate shop gives {@link #experiencePoints} at its sell rate.
     */
    static int experienceSellPoints(ExperienceView experience, int aur) {
        return experience.market()
                .map(market -> marketQuote(market, aur, market.sellFee(), true))
                .orElseGet(() -> experiencePoints(aur, experience.sellAur(), experience.sellPoints()));
    }

    /**
     * The points a player gets for paying {@code aur} Aur. A shop with a market quotes the curve at its buy fee, and gives 0
     * when the quote is empty; a fixed-rate shop gives {@link #experiencePoints} at its buy rate.
     */
    static int experienceBuyPoints(ExperienceView experience, int aur) {
        return experience.market()
                .map(market -> marketQuote(market, aur, market.buyFee(), false))
                .orElseGet(() -> experiencePoints(aur, experience.buyAur(), experience.buyPoints()));
    }

    /**
     * The Aur one press of a stepper moves for a sale or a purchase: the fixed rate's Aur, so the amount stays a whole round of
     * the rate, or 1 in market mode, where any whole Aur amount is a trade.
     */
    static int experienceStep(ExperienceView experience, boolean selling) {
        if (experience.market().isPresent()) {
            return 1;
        }
        return selling ? experience.sellAur() : experience.buyAur();
    }

    /** The market's live rate in hundredths of a point per Aur, clamped to an int. 0 when a reserve is out of range. */
    static int marketRate(MarketView market) {
        return rateOf(market.points(), market.aur());
    }

    /**
     * The price of 100 points in hundredths of an Aur, from a pool's reserves: {@code aur * 10000 / points}, clamped to an int.
     * 0 when either reserve is below 1.
     */
    static int priceHundredths(long points, long aur) {
        if (points < 1 || aur < 1) {
            return 0;
        }
        long scaled = aur > Long.MAX_VALUE / 10_000 ? Long.MAX_VALUE : aur * 10_000;
        return saturate(scaled / points);
    }

    /**
     * The price of 100 points in hundredths of an Aur, from a rate in hundredths of a point per Aur: {@code 1000000 / rate}.
     * 0 when the rate is not positive.
     */
    static int priceOfRate(int rateHundredths) {
        return rateHundredths > 0 ? 1_000_000 / rateHundredths : 0;
    }

    /**
     * The values of a price chart, oldest first: the price of each positive rate in {@code historyRates}, then the price at
     * {@code liveRate}. The live price is left out when the history's last rate already is {@code liveRate}, and when
     * {@code liveRate} is not positive.
     */
    static List<Integer> chartValues(List<Integer> historyRates, int liveRate) {
        List<Integer> values = new ArrayList<>(historyRates.size() + 1);
        for (int rate : historyRates) {
            if (rate > 0) {
                values.add(priceOfRate(rate));
            }
        }
        boolean liveInHistory = !historyRates.isEmpty() && historyRates.getLast() == liveRate;
        if (liveRate > 0 && !liveInHistory) {
            values.add(priceOfRate(liveRate));
        }
        return List.copyOf(values);
    }

    /**
     * One row per pixel column of a chart {@code width} wide and {@code height} tall, counted from the top, for the prices in
     * {@code values}. The scale runs from the lowest to the highest of {@code values} and {@code base}, the usual price, so
     * the usual price is always inside the chart. Column {@code c} stands at {@code c * (n - 1) / (width - 1)} along the
     * {@code n} values, linearly between its neighbours; with one column it takes the last value. A row is rounded to the
     * nearest, halves up, and the bottom row is {@code height - 1}. When every price is the same, every row is
     * {@code (height - 1) / 2}. Empty when there are no values, or the width or height is below 1.
     */
    static int[] chartYs(List<Integer> values, int base, int width, int height) {
        if (values.isEmpty() || width < 1 || height < 1) {
            return new int[0];
        }
        long low = lowest(values, base);
        long span = highest(values, base) - low;
        int count = values.size();
        int[] rows = new int[width];
        for (int column = 0; column < width; column++) {
            long steps = 1;
            long remainder = 0;
            int index = count - 1;
            if (width > 1) {
                steps = width - 1;
                long position = (long) column * (count - 1);
                index = (int) (position / steps);
                remainder = position % steps;
            }
            long from = values.get(index);
            long to = index + 1 < count ? values.get(index + 1) : from;
            rows[column] = rowOf((from - low) * steps + (to - from) * remainder, steps, span, height);
        }
        return rows;
    }

    /**
     * The row of the usual price {@code base} in the chart that {@link #chartYs} lays out for {@code values}, counted from the
     * top. {@code height} is at least 1.
     */
    static int chartBaseY(List<Integer> values, int base, int height) {
        long low = lowest(values, base);
        long span = highest(values, base) - low;
        return rowOf(base - low, 1, span, height);
    }

    /**
     * How far {@code rate} is from {@code base}, in whole percent of {@code base}, rounded to the nearest and halves away from
     * zero. 0 when {@code base} is not positive.
     */
    static int changePercent(int rate, int base) {
        if (base <= 0) {
            return 0;
        }
        long change = ((long) rate - base) * 100;
        long size = Math.abs(change);
        long percent = size / base;
        if (2 * (size % base) >= base) {
            percent++;
        }
        return (int) (change < 0 ? -percent : percent);
    }

    /** A rate in hundredths as text with two decimals: 5100 is {@code 51.00}, and 7 is {@code 0.07}. Not for negative values. */
    static String hundredths(int value) {
        return String.format(Locale.ROOT, "%d.%02d", value / 100, value % 100);
    }

    /**
     * The points a trade at a market's curve moves: the quote for {@code aur} at {@code feePercent}, or 0 when the direction is
     * not traded ({@code feePercent} -1), when {@code aur} is below 1, or when the curve gives no quote.
     */
    private static int marketQuote(MarketView market, int aur, int feePercent, boolean selling) {
        if (aur < 1 || feePercent < 0 || feePercent > 50) {
            return 0;
        }
        Optional<Reserves> reserves = reservesOf(market.points(), market.aur());
        if (reserves.isEmpty()) {
            return 0;
        }
        OptionalInt points = selling
                ? ReserveCurve.sellPoints(reserves.get(), aur, feePercent)
                : ReserveCurve.buyPoints(reserves.get(), aur, feePercent);
        return points.orElse(0);
    }

    /** The rate of a pool in hundredths, clamped to an int. 0 when a reserve is out of range. */
    private static int rateOf(long points, long aur) {
        return reservesOf(points, aur)
                .map(reserves -> saturate(ReserveCurve.rateHundredths(reserves)))
                .orElse(0);
    }

    /** The reserves of a pool, or empty when either is outside the curve's range. A view the server sends is always inside it. */
    private static Optional<Reserves> reservesOf(long points, long aur) {
        if (points < 1 || points > ReserveCurve.MAX_RESERVE || aur < 1 || aur > ReserveCurve.MAX_RESERVE) {
            return Optional.empty();
        }
        return Optional.of(new Reserves(points, aur));
    }

    /** The lowest of {@code values} and {@code base}. */
    private static long lowest(List<Integer> values, int base) {
        long low = base;
        for (int value : values) {
            low = Math.min(low, value);
        }
        return low;
    }

    /** The highest of {@code values} and {@code base}. */
    private static long highest(List<Integer> values, int base) {
        long high = base;
        for (int value : values) {
            high = Math.max(high, value);
        }
        return high;
    }

    /**
     * The row of {@code offset / denominator} above the bottom of a scale {@code span} wide, in a chart {@code height} tall:
     * {@code (height - 1) - round(offset * (height - 1) / (denominator * span))}, halves rounded up. Every row is
     * {@code (height - 1) / 2} when the span is 0. {@code offset} is not negative.
     */
    private static int rowOf(long offset, long denominator, long span, int height) {
        long top = height - 1;
        if (span <= 0) {
            return (int) (top / 2);
        }
        long numerator = offset * top;
        long divisor = denominator * span;
        long distance = Math.floorDiv(2 * numerator + divisor, 2 * divisor);
        return (int) (top - distance);
    }

    private static int saturate(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }
}
