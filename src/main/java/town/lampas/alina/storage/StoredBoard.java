package town.lampas.alina.storage;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/**
 * One board's commissions on one day, in board order, with the revision and seed they were drawn from. The list is an
 * immutable copy.
 */
public record StoredBoard(
        LocalDate boardDate, Identifier boardId, long revisionId, long seed, List<Identifier> commissionIds) {

    public StoredBoard {
        Objects.requireNonNull(boardDate, "boardDate");
        Objects.requireNonNull(boardId, "boardId");
        commissionIds = List.copyOf(commissionIds);
    }
}
