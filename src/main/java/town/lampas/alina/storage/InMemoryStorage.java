package town.lampas.alina.storage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;

/**
 * Keeps all state in memory. It is the backend when no database is configured, and the one the contract tests run
 * against. Each call runs on a small executor and completes its future there, as the PostgreSQL backend does. One lock
 * guards the state, so calls from any thread see it consistently, and each call applies its changes all or none.
 * Closing the instance discards everything. The server's storage comes from {@link StorageModule}; GameTests build
 * their own instance so that they do not share state with it.
 */
public final class InMemoryStorage implements AlinaStorage {
    private static final int THREADS = 2;
    /** The order PostgreSQL gives {@code created_at, operation_id}: a UUID's text sorts as its bytes do. */
    private static final Comparator<StoredOperation> OLDEST_FIRST = Comparator
            .comparing((StoredOperation stored) -> stored.operation().createdAt())
            .thenComparing(stored -> stored.operation().operationId().toString());

    /** A board's key: one board per date and board id. */
    private record BoardKey(LocalDate date, Identifier boardId) {}

    private final Object lock = new Object();
    private final StorageExecutor executor =
            new StorageExecutor(THREADS, "alina-memory-storage-", StorageSupport.SHUTDOWN_GRACE);
    private final Map<UUID, Integer> reputation = new HashMap<>();
    private final Map<UUID, Map<Identifier, CommissionState>> commissions = new HashMap<>();
    private final Map<UUID, Map<Identifier, AchievementState>> achievements = new HashMap<>();
    private final Map<UUID, Map<CriterionKey, Integer>> criteria = new HashMap<>();
    private final Map<UUID, StoredOperation> operations = new HashMap<>();
    private final Map<BoardKey, StoredBoard> boards = new HashMap<>();

    @Override
    public CompletableFuture<CharacterState> loadCharacter(UUID characterId) {
        return locked(() -> new CharacterState(
                characterId,
                reputation.getOrDefault(characterId, 0),
                commissions.getOrDefault(characterId, Map.of()),
                achievements.getOrDefault(characterId, Map.of()),
                criteria.getOrDefault(characterId, Map.of())));
    }

    @Override
    public CompletableFuture<Void> setReputation(UUID characterId, int value, Instant at) {
        return locked(() -> {
            reputation.put(characterId, value);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> acceptCommission(
            UUID characterId, Identifier commission, long revisionId, Instant acceptedAt) {
        Instant accepted = StorageSupport.micros(acceptedAt);
        return locked(() -> {
            commissionsOf(characterId).put(commission, new CommissionState(
                    commission, CommissionStatus.ACTIVE, 0, accepted, Optional.empty(), Optional.empty(), revisionId));
            return null;
        });
    }

    @Override
    public CompletableFuture<Boolean> abandonCommission(UUID characterId, Identifier commission) {
        return locked(() -> {
            Map<Identifier, CommissionState> byId = commissions.get(characterId);
            CommissionState row = byId == null ? null : byId.get(commission);
            if (row == null || row.status() != CommissionStatus.ACTIVE) {
                return false;
            }
            byId.remove(commission);
            return true;
        });
    }

    @Override
    public CompletableFuture<Void> writeCommissionProgress(UUID characterId, Map<Identifier, Integer> progress) {
        Map<Identifier, Integer> ordered = Map.copyOf(progress);
        return locked(() -> {
            for (Integer value : ordered.values()) {
                StorageSupport.requireNonNegative(value, "progress");
            }
            Map<Identifier, CommissionState> byId = commissions.get(characterId);
            if (byId == null) {
                return null;
            }
            for (Map.Entry<Identifier, Integer> entry : ordered.entrySet()) {
                CommissionState row = byId.get(entry.getKey());
                if (row != null && row.status() == CommissionStatus.ACTIVE) {
                    byId.put(entry.getKey(), new CommissionState(row.commissionId(), row.status(), entry.getValue(),
                            row.acceptedAt(), row.completedAt(), row.cooldownUntil(), row.revisionId()));
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> clearCommission(UUID characterId, Identifier commission) {
        return locked(() -> {
            Map<Identifier, CommissionState> byId = commissions.get(characterId);
            if (byId != null) {
                byId.remove(commission);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> writeAchievement(UUID characterId, AchievementState achievement, Instant at) {
        AchievementState stored = StorageSupport.micros(achievement);
        return locked(() -> {
            achievementsOf(characterId).put(stored.achievementId(), stored);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> deleteAchievement(UUID characterId, Identifier achievementId) {
        return locked(() -> {
            Map<Identifier, AchievementState> byId = achievements.get(characterId);
            if (byId != null) {
                byId.remove(achievementId);
            }
            removeCriteria(characterId, achievementId);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> writeCriterionProgress(
            UUID characterId, Map<CriterionKey, Integer> counts, Instant at) {
        Map<CriterionKey, Integer> ordered = Map.copyOf(counts);
        return locked(() -> {
            for (Map.Entry<CriterionKey, Integer> entry : ordered.entrySet()) {
                StorageSupport.requireNoNul(entry.getKey().criterion(), "criterion");
                StorageSupport.requireNonNegative(entry.getValue(), "criterion count");
            }
            Map<CriterionKey, Integer> rows = criteriaOf(characterId);
            for (Map.Entry<CriterionKey, Integer> entry : ordered.entrySet()) {
                if (entry.getValue() == 0) {
                    rows.remove(entry.getKey());
                } else {
                    rows.put(entry.getKey(), entry.getValue());
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> resetCriterionProgress(UUID characterId, Identifier achievementId) {
        return locked(() -> {
            removeCriteria(characterId, achievementId);
            return null;
        });
    }

    @Override
    public CompletableFuture<ApplyResult> applyOperation(Operation operation) {
        return locked(() -> {
            // Normalising can refuse the operation, before anything is changed.
            Operation stored = StorageSupport.normalised(operation);
            UUID character = stored.characterId();
            if (operations.containsKey(stored.operationId())) {
                return new ApplyResult(false, reputation.getOrDefault(character, 0));
            }
            // Every value that can fail is computed before the first put, so a failure leaves the state as it was.
            int next = Math.addExact(reputation.getOrDefault(character, 0), stored.reputationDelta());
            Runnable change = switch (stored.effect()) {
                case OperationEffect.CommissionSettled settled -> {
                    CommissionState row = settledRow(stored, settled);
                    yield () -> commissionsOf(character).put(row.commissionId(), row);
                }
                case OperationEffect.AchievementEarned earned -> {
                    AchievementState row = earnedRow(stored, earned);
                    yield () -> achievementsOf(character).put(row.achievementId(), row);
                }
            };
            operations.put(stored.operationId(),
                    new StoredOperation(stored, OperationStep.RECORDED, stored.createdAt(), Optional.empty()));
            reputation.put(character, next);
            change.run();
            return new ApplyResult(true, next);
        });
    }

    @Override
    public CompletableFuture<Void> markOperation(UUID operationId, OperationStep step, Optional<String> note, Instant at) {
        Instant stamp = StorageSupport.micros(at);
        return locked(() -> {
            note.ifPresent(text -> StorageSupport.requireNoNul(text, "note"));
            StoredOperation existing = operations.get(operationId);
            if (existing == null) {
                throw new IllegalArgumentException("No operation " + operationId);
            }
            operations.put(operationId, new StoredOperation(existing.operation(), step, stamp, note));
            return null;
        });
    }

    @Override
    public CompletableFuture<Optional<StoredOperation>> loadOperation(UUID operationId) {
        return locked(() -> Optional.ofNullable(operations.get(operationId)));
    }

    @Override
    public CompletableFuture<List<StoredOperation>> listOperations(Set<OperationStep> steps, int limit) {
        if (limit < 1) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("limit must be positive, was " + limit));
        }
        Set<OperationStep> wanted = Set.copyOf(steps);
        return locked(() -> operations.values().stream()
                .filter(stored -> wanted.contains(stored.step()))
                .sorted(OLDEST_FIRST)
                .limit(limit)
                .toList());
    }

    @Override
    public CompletableFuture<Optional<StoredBoard>> loadBoard(LocalDate boardDate, Identifier boardId) {
        return locked(() -> Optional.ofNullable(boards.get(new BoardKey(boardDate, boardId))));
    }

    @Override
    public CompletableFuture<StoredBoard> storeBoard(StoredBoard board) {
        return locked(() -> boards.computeIfAbsent(new BoardKey(board.boardDate(), board.boardId()), key -> board));
    }

    @Override
    public void close() {
        executor.close();
    }

    /** Runs the work on the executor while holding the lock. */
    private <T> CompletableFuture<T> locked(Supplier<T> work) {
        return executor.submit(() -> {
            synchronized (lock) {
                return work.get();
            }
        });
    }

    private Map<Identifier, CommissionState> commissionsOf(UUID characterId) {
        return commissions.computeIfAbsent(characterId, id -> new HashMap<>());
    }

    private Map<Identifier, AchievementState> achievementsOf(UUID characterId) {
        return achievements.computeIfAbsent(characterId, id -> new HashMap<>());
    }

    private Map<CriterionKey, Integer> criteriaOf(UUID characterId) {
        return criteria.computeIfAbsent(characterId, id -> new HashMap<>());
    }

    private void removeCriteria(UUID characterId, Identifier achievementId) {
        Map<CriterionKey, Integer> counts = criteria.get(characterId);
        if (counts != null) {
            counts.keySet().removeIf(key -> key.achievementId().equals(achievementId));
        }
    }

    /**
     * The row a turn-in leaves: the commission is settled, keeping the accepted time and revision of a row that exists.
     * Reads only, so it can run before any change.
     */
    private CommissionState settledRow(Operation operation, OperationEffect.CommissionSettled settled) {
        CommissionState existing =
                commissions.getOrDefault(operation.characterId(), Map.of()).get(settled.commissionId());
        Instant acceptedAt = existing == null ? operation.createdAt() : existing.acceptedAt();
        long revisionId = existing == null ? operation.revisionId() : existing.revisionId();
        return new CommissionState(settled.commissionId(), settled.status(), 0,
                acceptedAt, Optional.of(operation.createdAt()), settled.cooldownUntil(), revisionId);
    }

    /**
     * The row an earn leaves: the count goes up by one, the tier keeps the highest, and the availability is the earn's, unless
     * the stored tier is above the earn's tier, when the stored availability stays. Reads only, so an overflowing count throws
     * before any change.
     */
    private AchievementState earnedRow(Operation operation, OperationEffect.AchievementEarned earned) {
        AchievementState existing = achievements.getOrDefault(operation.characterId(), Map.of())
                .get(earned.achievementId());
        int tier = existing == null ? earned.tier() : Math.max(existing.tier(), earned.tier());
        int earnCount = existing == null ? 1 : Math.addExact(existing.earnCount(), 1);
        Optional<Instant> availableAt = existing != null && existing.tier() > earned.tier()
                ? existing.availableAt()
                : earned.availableAt();
        return new AchievementState(earned.achievementId(), tier, earnCount,
                Optional.of(operation.createdAt()), availableAt);
    }
}
