package town.lampas.alina.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * One achievement a character has earned at least once. {@code tier} is the highest tier earned in the current cycle. An
 * achievement with no tiers stores 1 while it is earned in its current cycle, and 0 once its cycle has started, so a stored
 * tier of 1 or more is what makes it earned in the cycle. A tier left from when the achievement had tiers is kept.
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
