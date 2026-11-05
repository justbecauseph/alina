package town.lampas.alina.storage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.resources.Identifier;

/**
 * Durable state behind Alina. Every method is asynchronous and must never block the server thread; implementations
 * complete their futures off-thread. Callers hop back to the server thread themselves before touching game state.
 *
 * <p>{@code characterId} is the player UUID until the Lampas character lifecycle (ADR-006 action item 9) defines a
 * separate character key.
 *
 * <p>Two implementations ship: in-memory (tests, single-player, no database configured) and PostgreSQL. Instants are
 * stored to the microsecond. A failing call leaves the state as it was.
 */
public interface AlinaStorage extends AutoCloseable {

    /** The character's commissions, achievements, criterion counts and reputation, as one snapshot. */
    CompletableFuture<CharacterState> loadCharacter(UUID characterId);

    /** Sets the reputation outright, for staff. Negative values are stored as given. */
    CompletableFuture<Void> setReputation(UUID characterId, int reputation, Instant at);

    /**
     * Makes the commission {@code ACTIVE} with no progress, and clears its completion and cooldown. Accepting a commission
     * whose row exists in any status replaces that row.
     */
    CompletableFuture<Void> acceptCommission(
            UUID characterId, Identifier commission, long revisionId, Instant acceptedAt);

    /** Deletes the commission only while it is {@code ACTIVE}. Completes with true when a row was deleted. */
    CompletableFuture<Boolean> abandonCommission(UUID characterId, Identifier commission);

    /**
     * Sets the progress of the character's {@code ACTIVE} commissions. Entries for other commissions are ignored. All of
     * them apply, or none.
     */
    CompletableFuture<Void> writeCommissionProgress(UUID characterId, Map<Identifier, Integer> progress);

    /** Deletes the commission whatever its status, once its cooldown has passed or content removed it. */
    CompletableFuture<Void> clearCommission(UUID characterId, Identifier commission);

    /** Stores the achievement's state as given, replacing any state stored before. {@code at} is the time of the write. */
    CompletableFuture<Void> writeAchievement(UUID characterId, AchievementState achievement, Instant at);

    /** Deletes the achievement and its criterion counts. */
    CompletableFuture<Void> deleteAchievement(UUID characterId, Identifier achievementId);

    /** Stores criterion counts. A count of 0 deletes its row. All of them apply, or none. */
    CompletableFuture<Void> writeCriterionProgress(UUID characterId, Map<CriterionKey, Integer> counts, Instant at);

    /** Deletes the criterion counts of one achievement and keeps the achievement itself. */
    CompletableFuture<Void> resetCriterionProgress(UUID characterId, Identifier achievementId);

    /**
     * Applies an operation in one transaction, keyed on its operation id. The first call with an id records the operation,
     * adds its reputation delta and applies its effect. A later call with the same id changes nothing and completes with
     * {@code fresh} false, even when its content differs.
     */
    CompletableFuture<ApplyResult> applyOperation(Operation operation);

    /**
     * Sets the step of a recorded operation, and its note: an empty note clears it. Fails when no operation has that id.
     */
    CompletableFuture<Void> markOperation(UUID operationId, OperationStep step, Optional<String> note, Instant at);

    CompletableFuture<Optional<StoredOperation>> loadOperation(UUID operationId);

    /** Up to {@code limit} operations at one of the given steps, the oldest {@code createdAt} first. */
    CompletableFuture<List<StoredOperation>> listOperations(Set<OperationStep> steps, int limit);

    CompletableFuture<Optional<StoredBoard>> loadBoard(LocalDate boardDate);

    /**
     * Stores the board for its date unless one is stored already. Completes with the board stored for that date, so two
     * callers that race end with the same board.
     */
    CompletableFuture<StoredBoard> storeBoard(StoredBoard board);

    @Override
    void close();
}
