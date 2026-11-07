package town.lampas.alina.achievement;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * Where one achievement stands for one character, as the achievement service holds it.
 *
 * @param earnedTiers how many of its advancements are earned in the current cycle: 0 or 1 for an achievement without
 *     tiers, 0 to the number of tiers for a tiered one. An earn whose reward settlement has not delivered yet counts.
 * @param highestTier the stored tier ({@code AchievementState.tier}): the highest tier earned in the current cycle, 0
 *     for an achievement without tiers
 * @param earnCount how many times it was earned in all cycles; each tier earn counts once
 * @param availableAt for a repeatable whose cycle is complete: when the next cycle starts
 * @param counts each criterion's count in the current cycle, by criterion name; a criterion with no count yet is 0
 * @param settling an earn of it is in the player's settlement escrow
 */
public record AchievementStanding(
        Identifier id,
        int earnedTiers,
        int highestTier,
        int earnCount,
        Optional<Instant> lastEarnedAt,
        Optional<Instant> availableAt,
        Map<String, Integer> counts,
        boolean settling) {

    public AchievementStanding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(lastEarnedAt, "lastEarnedAt");
        Objects.requireNonNull(availableAt, "availableAt");
        counts = Map.copyOf(counts);
        if (earnedTiers < 0 || highestTier < 0 || earnCount < 0) {
            throw new IllegalArgumentException("earnedTiers, highestTier and earnCount must not be negative");
        }
    }

    /** True once it has been earned at least once, in any cycle, or an earn is being settled. */
    public boolean everEarned() {
        return earnCount > 0 || settling;
    }

    /** The criterion's count in the current cycle, 0 when it has none. */
    public int count(String criterion) {
        return counts.getOrDefault(criterion, 0);
    }
}
