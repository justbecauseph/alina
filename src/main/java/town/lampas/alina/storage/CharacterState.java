package town.lampas.alina.storage;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/**
 * Everything Alina stores for one character, as one snapshot. The maps are immutable copies. A character with no rows
 * has reputation 0 and empty maps.
 */
public record CharacterState(
        UUID characterId,
        int reputation,
        Map<Identifier, CommissionState> commissions,
        Map<Identifier, AchievementState> achievements,
        Map<CriterionKey, Integer> criterionProgress) {

    public CharacterState {
        Objects.requireNonNull(characterId, "characterId");
        commissions = Map.copyOf(commissions);
        achievements = Map.copyOf(achievements);
        criterionProgress = Map.copyOf(criterionProgress);
    }
}
