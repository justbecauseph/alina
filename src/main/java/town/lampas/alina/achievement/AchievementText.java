package town.lampas.alina.achievement;

import net.minecraft.network.chat.Component;

/**
 * Text Alina adds to achievement descriptions. Translatable, with its keys in {@code assets/alina/lang/en_us.json} and
 * the same English text as the fallback.
 */
public final class AchievementText {

    private AchievementText() {}

    /** A repeatable without tiers: how many times it has been earned. */
    public static Component earnedTimes(int count) {
        return count == 1
                ? Component.translatableWithFallback("alina.achievement.earned_once", "Earned once")
                : Component.translatableWithFallback("alina.achievement.earned_times", "Earned %s times", count);
    }

    /** A tiered repeatable: how many times every tier has been earned. */
    public static Component completedTimes(int count) {
        return count == 1
                ? Component.translatableWithFallback("alina.achievement.completed_once", "Completed once")
                : Component.translatableWithFallback("alina.achievement.completed_times", "Completed %s times", count);
    }
}
