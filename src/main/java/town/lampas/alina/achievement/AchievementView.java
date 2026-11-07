package town.lampas.alina.achievement;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/**
 * A snapshot of one character's achievements, taken on the server thread: one standing for every achievement of the
 * active content, earned or not. The map is an immutable copy.
 */
public record AchievementView(UUID characterId, Map<Identifier, AchievementStanding> achievements) {

    public AchievementView {
        Objects.requireNonNull(characterId, "characterId");
        achievements = Map.copyOf(achievements);
    }
}
