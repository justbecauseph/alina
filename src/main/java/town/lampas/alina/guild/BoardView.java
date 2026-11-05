package town.lampas.alina.guild;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/**
 * Today's board (ALINA-PLAN §8.2).
 *
 * @param date the board day, which starts at the settings' reset time in their zone
 * @param revisionId the content revision the board was drawn under, 0 when none was stored
 * @param commissions the drawn commissions in board order, without those the active content no longer has as
 *     repeatables. An immutable copy.
 * @param stored false for a board with an empty rotation pool, which is not stored and is drawn again after the next
 *     content activation
 */
public record BoardView(LocalDate date, long revisionId, List<Identifier> commissions, boolean stored) {

    public BoardView {
        Objects.requireNonNull(date, "date");
        commissions = List.copyOf(commissions);
    }
}
