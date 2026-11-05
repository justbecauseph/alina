package town.lampas.alina.guild;

import java.util.Objects;
import net.minecraft.network.chat.Component;

/**
 * Why the Guild will not accept, turn in or abandon a commission now. {@code message} is the text shown to the player:
 * the reason of a grayed-out entry, or the chat message of a refused action.
 */
public record Refusal(Reason reason, Component message) {

    public Refusal {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(message, "message");
    }

    public enum Reason {
        /** The player's Guild record has not loaded yet. */
        LOADING,
        /** Today's board has not loaded yet. */
        BOARD_LOADING,
        /** The active content has no such commission. */
        UNKNOWN,
        /** A repeatable that is not on today's board. */
        NOT_ON_BOARD,
        /** Already active (accept). */
        ACTIVE,
        /** Not active (turn-in, abandon). */
        NOT_ACTIVE,
        /** Turned in, and settlement has not finished. */
        SETTLING,
        /** On cooldown. */
        COOLDOWN,
        /** A feat already completed. */
        COMPLETED,
        /** The player holds {@code active_cap} active commissions. */
        CAP_REACHED,
        /** An accept or abandon of this commission is still being written. */
        BUSY,
        /** The proof is not met now (turn-in). */
        PROOF_NOT_MET,
        /** Settlement refused to begin the turn-in; nothing changed. */
        SETTLEMENT_REFUSED
    }
}
