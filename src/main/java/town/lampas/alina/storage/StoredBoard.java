package town.lampas.alina.storage;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/**
 * The commissions on one day's board, in board order, with the revision and seed they were drawn from. The list is an
 * immutable copy.
 */
public record StoredBoard(LocalDate boardDate, long revisionId, long seed, List<Identifier> commissionIds) {

    public StoredBoard {
        Objects.requireNonNull(boardDate, "boardDate");
        commissionIds = List.copyOf(commissionIds);
    }
}
