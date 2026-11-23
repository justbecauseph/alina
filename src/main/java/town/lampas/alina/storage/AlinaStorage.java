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
     * adds its reputation delta and applies its effect. A trade's effect also adds its {@code aurPaid} to the budget of its
     * shop and day, and each purchase's count to the row of its offer and period, all in the same transaction. A trade's
     * {@code marketPoints} and {@code marketAur} add to the experience market's reserves in that transaction, exactly, and
     * are dropped when there is no market; a change that adds to the reserves also records a tick of them. A later call with
     * the same id changes nothing and
     * completes with {@code fresh} false, even when its content differs.
     */
    CompletableFuture<ApplyResult> applyOperation(Operation operation);

    /**
     * The character's trade rows that still count from {@code fromDay}: the budget rows of days on or after it, and the
     * purchase rows that are per character or per a day on or after it. A character with no trade rows has empty maps.
     */
    CompletableFuture<TradeState> loadTradeState(UUID characterId, LocalDate fromDay);

    /**
     * Deletes the character's trade budget rows for the day: those of one shop, or of every shop when {@code shopId} is
     * empty. The purchase rows are kept. For staff.
     */
    CompletableFuture<Void> resetTradeBudget(UUID characterId, Optional<Identifier> shopId, LocalDate tradeDay);

    /**
     * Sets the step of a recorded operation, and its note: an empty note clears it. Fails when no operation has that id.
     */
    CompletableFuture<Void> markOperation(UUID operationId, OperationStep step, Optional<String> note, Instant at);

    CompletableFuture<Optional<StoredOperation>> loadOperation(UUID operationId);

    /** Up to {@code limit} operations at one of the given steps, the oldest {@code createdAt} first. */
    CompletableFuture<List<StoredOperation>> listOperations(Set<OperationStep> steps, int limit);

    /** The board stored for the date and board id, or empty. Boards of other ids on the same date are not returned. */
    CompletableFuture<Optional<StoredBoard>> loadBoard(LocalDate boardDate, Identifier boardId);

    /**
     * Stores the board for its date and board id unless one is stored for them already. Completes with the board stored
     * for that date and board, so two callers that race end with the same board. Boards of other ids are not affected.
     */
    CompletableFuture<StoredBoard> storeBoard(StoredBoard board);

    /**
     * The market row, created from the seed with restockedDay = day when there is none. A second call returns the stored
     * row whatever seed it passes. Each seed reserve must be at least 1; the stored row is not bounded, since it holds the
     * exact sum of every delta applied to it.
     */
    CompletableFuture<MarketState> loadOrCreateMarket(long seedPoints, long seedAur, LocalDate day, Instant at);

    /**
     * Restocks the market for board day {@code day}, once: when the stored restockedDay is before {@code day}, adds the deltas
     * to the reserves exactly and sets restockedDay to {@code day}, and records a tick of the reserves after them, in one
     * transaction. Completes with whether it was applied. False when there is no row, and then nothing is recorded.
     */
    CompletableFuture<Boolean> restockMarket(LocalDate day, long deltaPoints, long deltaAur, Instant at);

    /**
     * Adds the deltas to both reserves exactly, for staff, and records a tick of the reserves after them. restockedDay is
     * kept. Completes with whether it was applied: false when there is no market row, and nothing changes then.
     */
    CompletableFuture<Boolean> adjustMarket(long deltaPoints, long deltaAur, Instant at);

    /**
     * Up to {@code limit} ticks of the market, the newest first. Storage keeps the newest 64 ticks, so a larger limit returns
     * fewer.
     */
    CompletableFuture<List<MarketTick>> loadMarketTicks(int limit);

    /** The market row, or empty when it was never created. Its reserves are the exact sums, whatever their value. */
    CompletableFuture<Optional<MarketState>> loadMarket();

    @Override
    void close();
}
