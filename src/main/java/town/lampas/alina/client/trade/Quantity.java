package town.lampas.alina.client.trade;

/**
 * How a quantity moves when the stepper's buttons are pressed, and what it is clamped to. A sale moves in multiples of the
 * row's {@code per}, from {@code per} up to the row's {@code maxCount}; a purchase or an experience amount moves one at a
 * time, from 1 up to the most allowed, and never below 1. Pure arithmetic, so it is unit tested.
 */
final class Quantity {
    /** A step count that reaches the top of any range. The max button moves by it, and the clamp picks the value. */
    static final int ALL = Integer.MAX_VALUE;
    /** The steps a Shift-click moves. */
    static final int SHIFT_STEPS = 10;

    private Quantity() {}

    /**
     * A sale quantity: {@code steps} multiples of {@code per} from {@code current}, held between {@code per} and
     * {@code maxCount}. A {@code current} that is not a multiple of {@code per} is rounded down first. Returns 0 when
     * {@code maxCount} is below {@code per}, which is a row that cannot be sold.
     */
    static int sell(int current, int steps, int per, int maxCount) {
        if (per <= 0 || maxCount < per) {
            return 0;
        }
        long index = Math.floorDiv(current, per) + (long) steps;
        return Math.clamp(index * per, per, maxCount);
    }

    /** A purchase quantity or an experience amount: {@code current + steps}, held between 1 and {@code max} (at least 1). */
    static int count(int current, int steps, int max) {
        return Math.clamp((long) current + steps, 1, Math.max(1, max));
    }

    /** The purchase quantity a reset sets: 1, as {@link #count} gives it from any start with any maximum. */
    static int resetCount(int max) {
        return count(1, 0, max);
    }

    /**
     * The sale quantity a reset sets: one {@code per} bundle, or 0 when {@code maxCount} is below {@code per}. An experience
     * amount's reset is the same, with {@code per} as one step of experience.
     */
    static int resetSell(int per, int maxCount) {
        return sell(per, 0, per, maxCount);
    }
}
