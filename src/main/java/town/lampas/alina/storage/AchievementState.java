package town.lampas.alina.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * One achievement a character has earned at least once. {@code tier} is the highest tier earned, and 0 for an achievement
 * with no tiers.
 */
public record AchievementState(
        Identifier achievementId,
        int tier,
        int earnCount,
        Optional<Instant> lastEarnedAt,
        Optional<Instant> availableAt) {

    public AchievementState {
        Objects.requireNonNull(achievementId, "achievementId");
        Objects.requireNonNull(lastEarnedAt, "lastEarnedAt");
        Objects.requireNonNull(availableAt, "availableAt");
        if (tier < 0) {
            throw new IllegalArgumentException("tier must not be negative, was " + tier);
        }
        if (earnCount < 0) {
            throw new IllegalArgumentException("earnCount must not be negative, was " + earnCount);
        }
    }
}
