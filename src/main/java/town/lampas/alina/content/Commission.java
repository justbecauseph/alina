package town.lampas.alina.content;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * A Guild commission (ALINA-PLAN §6.1).
 *
 * @param category the catalog category, {@code A} to {@code F}
 * @param cooldown the cooldown after a turn-in: always present for a repeatable (the settings' default when the content
 *     gives none), empty for a feat
 * @param weight the board rotation weight, at least 1
 * @param inRotation whether a repeatable can be drawn for the daily board
 */
public record Commission(
        Identifier id,
        char category,
        Kind kind,
        Component name,
        Component hint,
        Proof proof,
        Optional<Cooldown> cooldown,
        int weight,
        boolean inRotation,
        Reward reward) {

    public enum Kind {
        /** Drawn for the daily board, and accepted again after its cooldown. */
        REPEATABLE,
        /** Listed until completed, once per character. */
        FEAT
    }
}
