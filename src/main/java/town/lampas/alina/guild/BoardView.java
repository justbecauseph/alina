package town.lampas.alina.guild;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/**
 * One notice board's draw for today (ALINA-PLAN §8.2).
 *
 * @param boardId the board's id, as the content names it
 * @param date the board day, which starts at the settings' reset time in their zone
 * @param revisionId the content revision the board was drawn under, 0 when none was stored
 * @param commissions the drawn commissions in board order, without those that are no longer repeatables of this board's
 *     pool in the active content. An immutable copy.
 * @param stored false for a board with an empty rotation pool, which is not stored and is drawn again after the next
 *     content activation
 */
public record BoardView(Identifier boardId, LocalDate date, long revisionId, List<Identifier> commissions, boolean stored) {

    public BoardView {
        Objects.requireNonNull(boardId, "boardId");
        Objects.requireNonNull(date, "date");
        commissions = List.copyOf(commissions);
    }
}
