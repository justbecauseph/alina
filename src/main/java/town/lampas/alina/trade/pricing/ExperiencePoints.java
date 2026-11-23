package town.lampas.alina.trade.pricing;

import net.minecraft.world.entity.player.Player;

/**
 * The experience points a player holds, as vanilla counts them: the points of each level below the current one, and the
 * share of the current level that the progress is.
 */
public final class ExperiencePoints {
    /**
     * From this level up the total is past {@link Integer#MAX_VALUE}, so the sum is not computed: the sum of the points of
     * levels 0 to 29999 is over 4 billion.
     */
    private static final int SATURATED_LEVEL = 30_000;

    private ExperiencePoints() {}

    /**
     * The points to go from {@code level} to {@code level + 1}. The same formula as vanilla
     * {@code Player.getXpNeededForNextLevel}.
     *
     * @throws IllegalArgumentException when {@code level} is negative
     */
    public static int neededForLevel(int level) {
        if (level < 0) {
            throw new IllegalArgumentException("a level is not negative, was " + level);
        }
        if (level >= 30) {
            return 112 + (level - 30) * 9;
        }
        if (level >= 15) {
            return 37 + (level - 15) * 5;
        }
        return 7 + level * 2;
    }

    /**
     * The points a player at {@code level} with {@code progress} (0 up to but not including 1) holds: the points of every
     * level below {@code level}, plus the progress times the points of {@code level}, rounded down. Rounding down keeps the
     * total at or below what {@code giveExperiencePoints} takes away from the player before it reaches level 0 with no
     * progress. Saturates at {@link Integer#MAX_VALUE}.
     *
     * @throws IllegalArgumentException when {@code level} is negative or {@code progress} is outside [0, 1)
     */
    public static int total(int level, float progress) {
        if (level < 0) {
            throw new IllegalArgumentException("a level is not negative, was " + level);
        }
        if (!(progress >= 0f && progress < 1f)) {
            throw new IllegalArgumentException("progress is in [0, 1), was " + progress);
        }
        if (level >= SATURATED_LEVEL) {
            return Integer.MAX_VALUE;
        }
        // The float is exact in a double, and so is its product with a small integer, so the floor is exact.
        long share = (long) Math.floor((double) progress * neededForLevel(level));
        return (int) Math.min(Integer.MAX_VALUE, pointsBelow(level) + share);
    }

    /** The points the player holds: {@link #total(int, float)} for the player's level and progress. */
    public static int total(Player player) {
        return total(player.experienceLevel, player.experienceProgress);
    }

    /**
     * Where a point total stands: the level it reaches, the points into that level, and the points that level needs.
     *
     * @param level the level reached
     * @param into the points held beyond the start of {@code level}; from 0 up to but not including {@code needed}
     * @param needed the points to go from {@code level} to {@code level + 1}
     */
    public record Standing(int level, int into, int needed) {}

    /**
     * The standing of a player who holds {@code points} points: the level L with {@code pointsBelow(L) <= points} and
     * {@code points < pointsBelow(L + 1)}. This is the inverse of {@link #total(int, float)} at progress 0. The level is
     * found by binary search over the levels up to {@link #SATURATED_LEVEL}.
     *
     * @throws IllegalArgumentException when {@code points} is negative
     */
    public static Standing standing(int points) {
        if (points < 0) {
            throw new IllegalArgumentException("points are not negative, were " + points);
        }
        int low = 0;
        int high = SATURATED_LEVEL;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (pointsBelow(middle) <= points) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        // pointsBelow(low) is at most points, which is an int, so the cast is exact.
        int into = points - (int) pointsBelow(low);
        return new Standing(low, into, neededForLevel(low));
    }

    /** The points of levels 0 to {@code level - 1}, summed by segment: levels 0 to 14, 15 to 29, and 30 and up. */
    private static long pointsBelow(int level) {
        long below15 = Math.min(level, 15);
        long from15To29 = Math.max(0, Math.min(level, 30) - 15);
        long from30 = Math.max(0, level - 30);
        return 7 * below15 + below15 * (below15 - 1)
                + 37 * from15To29 + 5 * from15To29 * (from15To29 - 1) / 2
                + 112 * from30 + 9 * from30 * (from30 - 1) / 2;
    }
}
