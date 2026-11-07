package town.lampas.alina.achievement;

import java.util.List;
import town.lampas.alina.content.AchievementsContent.Achievement;
import town.lampas.alina.content.CriterionSpec;

/**
 * How far one advancement holder has got, in the segments the vanilla screen shows as {@code x/y} (ALINA-PLAN §9.4).
 * The segments are the holder's requirements: one per requirements group when the achievement has several groups (a
 * group's segment is done when the group is met), otherwise one per counted event of the target, at most
 * {@link #MAX_SEGMENTS}. For a target above that, segment i is done at {@code ceil(i * target / segments)} events.
 */
final class HolderProgress {
    /** The most segments a holder has. */
    static final int MAX_SEGMENTS = 1000;

    /** The state of a tab's root holder: always done, one segment. */
    static final State TAB = new State(true, 1, 1);

    private HolderProgress() {}

    /**
     * One holder's state. {@code doneSegments} is {@code segments} when {@code done}, and below it otherwise.
     *
     * @param done the holder is earned in the current cycle
     * @param segments the holder's segments, at least 1
     * @param doneSegments the segments done
     */
    record State(boolean done, int segments, int doneSegments) {
        State {
            if (segments < 1 || doneSegments < 0 || (done ? doneSegments != segments : doneSegments >= segments)) {
                throw new IllegalArgumentException("segments " + segments + ", done " + doneSegments + " when done is "
                        + done);
            }
        }
    }

    /**
     * The state of tier {@code tier} (1-based; 1 for an achievement without tiers) of {@code achievement} for a character
     * with {@code standing}. A null standing is nothing counted and nothing earned.
     */
    static State of(Achievement achievement, int tier, AchievementStanding standing) {
        boolean done = (standing == null ? 0 : standing.earnedTiers()) >= tier;
        List<List<String>> groups = achievement.tiers().isEmpty()
                ? achievement.requirements()
                : List.of(List.of(criterionOf(achievement)));
        if (groups.isEmpty()) {
            return new State(done, 1, done ? 1 : 0);
        }
        if (groups.size() > 1) {
            int met = 0;
            for (List<String> group : groups) {
                if (group.stream().anyMatch(name -> count(standing, name) >= target(achievement, tier, name))) {
                    met++;
                }
            }
            int segments = groups.size();
            return new State(done, segments, done ? segments : Math.min(met, segments - 1));
        }
        List<String> names = groups.get(0);
        long target = names.stream().mapToLong(name -> target(achievement, tier, name)).min().orElse(1);
        int segments = segments(target);
        long partial = names.stream()
                .mapToLong(name -> partial(count(standing, name), target(achievement, tier, name), segments))
                .max().orElse(0);
        return new State(done, segments, done ? segments : (int) Math.min(segments - 1, partial));
    }

    /** The segments of a holder whose target is {@code target} events: one per event, at most {@link #MAX_SEGMENTS}. */
    static int segments(long target) {
        return (int) Math.min(MAX_SEGMENTS, Math.max(1, target));
    }

    /** The segments done after {@code count} events toward {@code target}, as {@link #segments} counts them. */
    static int doneSegments(long target, long count) {
        long positive = Math.max(1, target);
        return (int) partial(count, positive, segments(positive));
    }

    /** The criterion of a tiered achievement: the one it has. */
    private static String criterionOf(Achievement achievement) {
        return achievement.criteria().keySet().stream().findFirst().orElse("");
    }

    /** The count a criterion needs: the tier's count for a tiered achievement, else the criterion's own count. */
    private static long target(Achievement achievement, int tier, String name) {
        if (!achievement.tiers().isEmpty()) {
            return Math.max(1, achievement.tiers().get(tier - 1).count());
        }
        CriterionSpec spec = achievement.criteria().get(name);
        return spec == null ? 1 : Math.max(1, spec.count());
    }

    private static int count(AchievementStanding standing, String name) {
        return standing == null ? 0 : standing.count(name);
    }

    /** Segments done for {@code count} of {@code target}, rounded down: {@code min(count, target) * segments / target}. */
    private static long partial(long count, long target, int segments) {
        return Math.max(0, Math.min(count, target)) * segments / target;
    }
}
