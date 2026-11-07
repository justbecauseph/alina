package town.lampas.alina.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.resources.Identifier;

/**
 * Stores Alina state in PostgreSQL through a HikariCP pool. Every call runs on a dedicated executor, never on the server
 * thread. The executor has as many threads as the pool has connections, so a task that starts work always gets a
 * connection. The pool's default schema is the configured schema, so the migrations and every query use unqualified
 * table names. Call {@link #migrate()} before first use.
 */
final class PostgresStorage implements AlinaStorage {
    /**
     * How long one read from the server may wait before the connection fails. A server that stops answering otherwise
     * holds its thread and connection forever. A {@code socketTimeout} in the {@code jdbc_url} overrides this, but not
     * the two server limits below, which are derived from this value.
     */
    private static final int SOCKET_TIMEOUT_SECONDS = 10;
    /**
     * How long a statement may wait for a lock before the server fails it. Below the socket timeout, so the server's
     * error reaches the client before the client's read fails. A failed statement is rolled back, so a write the client
     * has given up on cannot commit later, when the lock it waited on is released.
     */
    private static final int LOCK_TIMEOUT_SECONDS = SOCKET_TIMEOUT_SECONDS / 2;
    /** How long a statement may run before the server cancels it. Below the socket timeout, for the same reason. */
    private static final int STATEMENT_TIMEOUT_SECONDS = SOCKET_TIMEOUT_SECONDS - 2;
    /** A character read sees one snapshot, so its reputation, commissions and achievements agree. */
    private static final String SNAPSHOT = "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY";

    private static final String SELECT_REPUTATION = "SELECT reputation FROM character_state WHERE character_id = ?";
    private static final String UPSERT_REPUTATION = """
            INSERT INTO character_state (character_id, reputation, updated_at) VALUES (?, ?, ?)
            ON CONFLICT (character_id) DO UPDATE SET reputation = EXCLUDED.reputation, updated_at = EXCLUDED.updated_at
            """;
    private static final String ADD_REPUTATION = """
            INSERT INTO character_state (character_id, reputation, updated_at) VALUES (?, ?, ?)
            ON CONFLICT (character_id) DO UPDATE
                SET reputation = character_state.reputation + EXCLUDED.reputation, updated_at = EXCLUDED.updated_at
            RETURNING reputation
            """;
    private static final String SELECT_COMMISSIONS = """
            SELECT commission_id, status, progress, accepted_at, completed_at, cooldown_until, revision_id
            FROM commission_state WHERE character_id = ?
            """;
    private static final String ACCEPT_COMMISSION = """
            INSERT INTO commission_state
                (character_id, commission_id, status, progress, accepted_at, completed_at, cooldown_until, revision_id)
            VALUES (?, ?, 'active', 0, ?, NULL, NULL, ?)
            ON CONFLICT (character_id, commission_id) DO UPDATE SET
                status = 'active', progress = 0, accepted_at = EXCLUDED.accepted_at,
                completed_at = NULL, cooldown_until = NULL, revision_id = EXCLUDED.revision_id
            """;
    private static final String DELETE_ACTIVE_COMMISSION =
            "DELETE FROM commission_state WHERE character_id = ? AND commission_id = ? AND status = 'active'";
    private static final String DELETE_COMMISSION =
            "DELETE FROM commission_state WHERE character_id = ? AND commission_id = ?";
    private static final String SET_PROGRESS = """
            UPDATE commission_state SET progress = ?
            WHERE character_id = ? AND commission_id = ? AND status = 'active'
            """;
    private static final String SETTLE_COMMISSION = """
            INSERT INTO commission_state
                (character_id, commission_id, status, progress, accepted_at, completed_at, cooldown_until, revision_id)
            VALUES (?, ?, ?, 0, ?, ?, ?, ?)
            ON CONFLICT (character_id, commission_id) DO UPDATE SET
                status = EXCLUDED.status, progress = 0, completed_at = EXCLUDED.completed_at,
                cooldown_until = EXCLUDED.cooldown_until
            """;
    private static final String SELECT_ACHIEVEMENTS = """
            SELECT achievement_id, tier, earn_count, last_earned_at, available_at
            FROM achievement WHERE character_id = ?
            """;
    private static final String UPSERT_ACHIEVEMENT = """
            INSERT INTO achievement
                (character_id, achievement_id, tier, earn_count, last_earned_at, available_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (character_id, achievement_id) DO UPDATE SET
                tier = EXCLUDED.tier, earn_count = EXCLUDED.earn_count, last_earned_at = EXCLUDED.last_earned_at,
                available_at = EXCLUDED.available_at, updated_at = EXCLUDED.updated_at
            """;
    private static final String EARN_ACHIEVEMENT = """
            INSERT INTO achievement
                (character_id, achievement_id, tier, earn_count, last_earned_at, available_at, updated_at)
            VALUES (?, ?, ?, 1, ?, ?, ?)
            ON CONFLICT (character_id, achievement_id) DO UPDATE SET
                tier = GREATEST(achievement.tier, EXCLUDED.tier), earn_count = achievement.earn_count + 1,
                last_earned_at = EXCLUDED.last_earned_at,
                available_at = CASE WHEN achievement.tier > EXCLUDED.tier
                    THEN achievement.available_at ELSE EXCLUDED.available_at END,
                updated_at = EXCLUDED.updated_at
            """;
    private static final String DELETE_ACHIEVEMENT =
            "DELETE FROM achievement WHERE character_id = ? AND achievement_id = ?";
    private static final String SELECT_CRITERIA =
            "SELECT achievement_id, criterion, count FROM criterion_progress WHERE character_id = ?";
    private static final String UPSERT_CRITERION = """
            INSERT INTO criterion_progress (character_id, achievement_id, criterion, count, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (character_id, achievement_id, criterion) DO UPDATE SET
                count = EXCLUDED.count, updated_at = EXCLUDED.updated_at
            """;
    private static final String DELETE_CRITERION = """
            DELETE FROM criterion_progress WHERE character_id = ? AND achievement_id = ? AND criterion = ?
            """;
    private static final String DELETE_CRITERIA_OF_ACHIEVEMENT =
            "DELETE FROM criterion_progress WHERE character_id = ? AND achievement_id = ?";

    private static final String OPERATION_COLUMNS = "operation_id, character_id, kind, subject_id, tier, revision_id, "
            + "world_id, reward, reputation, effect, step, created_at, updated_at, note";
    private static final String INSERT_OPERATION = """
            INSERT INTO operation (operation_id, character_id, kind, subject_id, tier, revision_id, world_id, reward,
                reputation, effect, step, created_at, updated_at, note)
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), ?, ?, ?, ?)
            ON CONFLICT (operation_id) DO NOTHING
            """;
    private static final String SELECT_OPERATION =
            "SELECT " + OPERATION_COLUMNS + " FROM operation WHERE operation_id = ?";
    private static final String LIST_OPERATIONS = "SELECT " + OPERATION_COLUMNS
            + " FROM operation WHERE step = ANY(?) ORDER BY created_at, operation_id LIMIT ?";
    private static final String MARK_OPERATION =
            "UPDATE operation SET step = ?, note = ?, updated_at = ? WHERE operation_id = ?";

    private static final String SELECT_BOARD =
            "SELECT board_date, revision_id, seed, commission_ids FROM board WHERE board_date = ?";
    private static final String INSERT_BOARD = """
            INSERT INTO board (board_date, revision_id, seed, commission_ids) VALUES (?, ?, ?, ?)
            ON CONFLICT (board_date) DO NOTHING
            """;

    private final String schema;
    private final HikariDataSource dataSource;
    private final StorageExecutor executor;

    PostgresStorage(String jdbcUrl, String username, String password, String schema, int poolSize) {
        if (!StorageSupport.isSchemaName(schema)) {
            throw new IllegalArgumentException("Schema name " + schema + " is not a lowercase identifier");
        }
        this.schema = schema;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setSchema(schema);
        config.setMaximumPoolSize(poolSize);
        config.setPoolName("alina-storage");
        // Driver defaults. The PostgreSQL driver gives a URL parameter of the same name priority over these.
        config.addDataSourceProperty("socketTimeout", String.valueOf(SOCKET_TIMEOUT_SECONDS));
        config.addDataSourceProperty("tcpKeepAlive", "true");
        // Startup parameters of every connection, so the server answers with an error before socketTimeout fires.
        config.addDataSourceProperty("options", "-c lock_timeout=" + LOCK_TIMEOUT_SECONDS + "s -c statement_timeout="
                + STATEMENT_TIMEOUT_SECONDS + "s");
        this.dataSource = new HikariDataSource(config);
        this.executor = new StorageExecutor(poolSize, "alina-storage-", StorageSupport.SHUTDOWN_GRACE);
    }

    /**
     * Applies the migrations the database has not seen yet. Blocks until they finish, so call it when the server
     * starts, never on a tick. A failure stops the server start, because the schema is then not usable.
     */
    void migrate() {
        try {
            PostgresMigrations.apply(dataSource, schema);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Alina storage could not migrate schema " + schema + ": " + e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<CharacterState> loadCharacter(UUID characterId) {
        return inSnapshot(connection -> {
            int reputation = reputationOf(connection, characterId);
            Map<Identifier, CommissionState> commissions = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_COMMISSIONS)) {
                statement.setObject(1, characterId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        CommissionState state = commission(rows);
                        commissions.put(state.commissionId(), state);
                    }
                }
            }
            Map<Identifier, AchievementState> achievements = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ACHIEVEMENTS)) {
                statement.setObject(1, characterId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        AchievementState state = achievement(rows);
                        achievements.put(state.achievementId(), state);
                    }
                }
            }
            Map<CriterionKey, Integer> criteria = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_CRITERIA)) {
                statement.setObject(1, characterId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        criteria.put(new CriterionKey(Identifier.parse(rows.getString(1)), rows.getString(2)),
                                rows.getInt(3));
                    }
                }
            }
            return new CharacterState(characterId, reputation, commissions, achievements, criteria);
        });
    }

    @Override
    public CompletableFuture<Void> setReputation(UUID characterId, int reputation, Instant at) {
        OffsetDateTime stamp = utc(StorageSupport.micros(at));
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT_REPUTATION)) {
                statement.setObject(1, characterId);
                statement.setInt(2, reputation);
                statement.setObject(3, stamp);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> acceptCommission(
            UUID characterId, Identifier commission, long revisionId, Instant acceptedAt) {
        OffsetDateTime accepted = utc(StorageSupport.micros(acceptedAt));
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(ACCEPT_COMMISSION)) {
                statement.setObject(1, characterId);
                statement.setString(2, commission.toString());
                statement.setObject(3, accepted);
                statement.setLong(4, revisionId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Boolean> abandonCommission(UUID characterId, Identifier commission) {
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_ACTIVE_COMMISSION)) {
                statement.setObject(1, characterId);
                statement.setString(2, commission.toString());
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Void> writeCommissionProgress(UUID characterId, Map<Identifier, Integer> progress) {
        Map<Identifier, Integer> ordered = Map.copyOf(progress);
        return inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SET_PROGRESS)) {
                for (Map.Entry<Identifier, Integer> entry : ordered.entrySet()) {
                    StorageSupport.requireNonNegative(entry.getValue(), "progress");
                    statement.setInt(1, entry.getValue());
                    statement.setObject(2, characterId);
                    statement.setString(3, entry.getKey().toString());
                    statement.executeUpdate();
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> clearCommission(UUID characterId, Identifier commission) {
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_COMMISSION)) {
                statement.setObject(1, characterId);
                statement.setString(2, commission.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> writeAchievement(UUID characterId, AchievementState achievement, Instant at) {
        OffsetDateTime stamp = utc(StorageSupport.micros(at));
        AchievementState stored = StorageSupport.micros(achievement);
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT_ACHIEVEMENT)) {
                statement.setObject(1, characterId);
                statement.setString(2, stored.achievementId().toString());
                statement.setInt(3, stored.tier());
                statement.setInt(4, stored.earnCount());
                setOptionalInstant(statement, 5, stored.lastEarnedAt());
                setOptionalInstant(statement, 6, stored.availableAt());
                statement.setObject(7, stamp);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> deleteAchievement(UUID characterId, Identifier achievementId) {
        return inTransaction(connection -> {
            deleteCriteriaOf(connection, characterId, achievementId);
            try (PreparedStatement statement = connection.prepareStatement(DELETE_ACHIEVEMENT)) {
                statement.setObject(1, characterId);
                statement.setString(2, achievementId.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> writeCriterionProgress(
            UUID characterId, Map<CriterionKey, Integer> counts, Instant at) {
        Map<CriterionKey, Integer> ordered = Map.copyOf(counts);
        OffsetDateTime stamp = utc(StorageSupport.micros(at));
        return inTransaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement(UPSERT_CRITERION);
                    PreparedStatement delete = connection.prepareStatement(DELETE_CRITERION)) {
                for (Map.Entry<CriterionKey, Integer> entry : ordered.entrySet()) {
                    CriterionKey key = entry.getKey();
                    int count = entry.getValue();
                    StorageSupport.requireNoNul(key.criterion(), "criterion");
                    StorageSupport.requireNonNegative(count, "criterion count");
                    if (count == 0) {
                        delete.setObject(1, characterId);
                        delete.setString(2, key.achievementId().toString());
                        delete.setString(3, key.criterion());
                        delete.executeUpdate();
                    } else {
                        upsert.setObject(1, characterId);
                        upsert.setString(2, key.achievementId().toString());
                        upsert.setString(3, key.criterion());
                        upsert.setInt(4, count);
                        upsert.setObject(5, stamp);
                        upsert.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> resetCriterionProgress(UUID characterId, Identifier achievementId) {
        return query(connection -> {
            deleteCriteriaOf(connection, characterId, achievementId);
            return null;
        });
    }

    @Override
    public CompletableFuture<ApplyResult> applyOperation(Operation operation) {
        return inTransaction(connection -> {
            // Normalising can refuse the operation, which rolls back the transaction before anything is written.
            Operation stored = StorageSupport.normalised(operation);
            int inserted;
            try (PreparedStatement insert = connection.prepareStatement(INSERT_OPERATION)) {
                insert.setObject(1, stored.operationId());
                insert.setObject(2, stored.characterId());
                insert.setString(3, StorageSupport.stored(stored.kind()));
                insert.setString(4, stored.subjectId().toString());
                insert.setInt(5, stored.tier());
                insert.setLong(6, stored.revisionId());
                insert.setString(7, stored.worldId());
                insert.setString(8, stored.rewardJson());
                insert.setInt(9, stored.reputationDelta());
                insert.setString(10, OperationEffectCodec.toJson(stored.effect()));
                insert.setString(11, StorageSupport.stored(OperationStep.RECORDED));
                insert.setObject(12, utc(stored.createdAt()));
                insert.setObject(13, utc(stored.createdAt()));
                insert.setNull(14, Types.VARCHAR);
                inserted = insert.executeUpdate();
            }
            // Zero rows: the id was recorded by an earlier, committed transaction, so nothing else applies.
            if (inserted == 0) {
                return new ApplyResult(false, reputationOf(connection, stored.characterId()));
            }
            int reputation = addReputation(connection, stored.characterId(), stored.reputationDelta(),
                    stored.createdAt());
            applyEffect(connection, stored);
            return new ApplyResult(true, reputation);
        });
    }

    @Override
    public CompletableFuture<Void> markOperation(UUID operationId, OperationStep step, Optional<String> note, Instant at) {
        OffsetDateTime stamp = utc(StorageSupport.micros(at));
        return inTransaction(connection -> {
            note.ifPresent(text -> StorageSupport.requireNoNul(text, "note"));
            try (PreparedStatement statement = connection.prepareStatement(MARK_OPERATION)) {
                statement.setString(1, StorageSupport.stored(step));
                setOptionalText(statement, 2, note);
                statement.setObject(3, stamp);
                statement.setObject(4, operationId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalArgumentException("No operation " + operationId);
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Optional<StoredOperation>> loadOperation(UUID operationId) {
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_OPERATION)) {
                statement.setObject(1, operationId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(storedOperation(rows));
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<StoredOperation>> listOperations(Set<OperationStep> steps, int limit) {
        if (limit < 1) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("limit must be positive, was " + limit));
        }
        String[] names = steps.stream().map(StorageSupport::stored).toArray(String[]::new);
        return query(connection -> {
            List<StoredOperation> listing = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(LIST_OPERATIONS)) {
                statement.setArray(1, connection.createArrayOf("text", names));
                statement.setInt(2, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        listing.add(storedOperation(rows));
                    }
                }
            }
            return List.copyOf(listing);
        });
    }

    @Override
    public CompletableFuture<Optional<StoredBoard>> loadBoard(LocalDate boardDate) {
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_BOARD)) {
                statement.setObject(1, boardDate);
                return firstBoard(statement);
            }
        });
    }

    @Override
    public CompletableFuture<StoredBoard> storeBoard(StoredBoard board) {
        return inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(INSERT_BOARD)) {
                insert.setObject(1, board.boardDate());
                insert.setLong(2, board.revisionId());
                insert.setLong(3, board.seed());
                insert.setArray(4, commissionArray(connection, board.commissionIds()));
                insert.executeUpdate();
            }
            // Inserted now, or inserted earlier by a committed transaction, so the row exists either way.
            try (PreparedStatement select = connection.prepareStatement(SELECT_BOARD)) {
                select.setObject(1, board.boardDate());
                return firstBoard(select).orElseThrow(
                        () -> new SQLException("Board " + board.boardDate() + " is missing after its insert"));
            }
        });
    }

    @Override
    public void close() {
        executor.close();
        dataSource.close();
    }

    /** A unit of JDBC work. Its result becomes the value of the returned future. */
    private interface Work<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** Runs the work on the executor with a pooled connection in autocommit mode. */
    private <T> CompletableFuture<T> query(Work<T> work) {
        return executor.submit(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return work.apply(connection);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        });
    }

    /** Runs the work on the executor in one transaction: committed when it returns, rolled back when it throws. */
    private <T> CompletableFuture<T> inTransaction(Work<T> work) {
        return transaction(false, work);
    }

    /** As {@link #inTransaction}, reading one snapshot: the transaction sees the data as of its first statement. */
    private <T> CompletableFuture<T> inSnapshot(Work<T> work) {
        return transaction(true, work);
    }

    private <T> CompletableFuture<T> transaction(boolean snapshot, Work<T> work) {
        return executor.submit(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    if (snapshot) {
                        try (Statement statement = connection.createStatement()) {
                            statement.execute(SNAPSHOT);
                        }
                    }
                    T result = work.apply(connection);
                    connection.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        e.addSuppressed(rollbackFailure);
                    }
                    throw e;
                }
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        });
    }

    private static int reputationOf(Connection connection, UUID characterId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_REPUTATION)) {
            statement.setObject(1, characterId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    /** Adds the delta to the character's reputation, creating the row at the delta when there is none. */
    private static int addReputation(Connection connection, UUID characterId, int delta, Instant at)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ADD_REPUTATION)) {
            statement.setObject(1, characterId);
            statement.setInt(2, delta);
            statement.setObject(3, utc(at));
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private static void applyEffect(Connection connection, Operation operation) throws SQLException {
        switch (operation.effect()) {
            case OperationEffect.CommissionSettled settled -> settle(connection, operation, settled);
            case OperationEffect.AchievementEarned earned -> earn(connection, operation, earned);
        }
    }

    /** A turn-in. A row that exists keeps its accepted time and revision, and a new row takes the operation's. */
    private static void settle(Connection connection, Operation operation, OperationEffect.CommissionSettled settled)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SETTLE_COMMISSION)) {
            OffsetDateTime at = utc(operation.createdAt());
            statement.setObject(1, operation.characterId());
            statement.setString(2, settled.commissionId().toString());
            statement.setString(3, StorageSupport.stored(settled.status()));
            statement.setObject(4, at);
            statement.setObject(5, at);
            setOptionalInstant(statement, 6, settled.cooldownUntil());
            statement.setLong(7, operation.revisionId());
            statement.executeUpdate();
        }
    }

    /**
     * An earn. The tier keeps the highest, the count goes up by one, and the availability is the earn's unless the stored tier
     * is above the earn's, when the stored availability stays.
     */
    private static void earn(Connection connection, Operation operation, OperationEffect.AchievementEarned earned)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(EARN_ACHIEVEMENT)) {
            OffsetDateTime at = utc(operation.createdAt());
            statement.setObject(1, operation.characterId());
            statement.setString(2, earned.achievementId().toString());
            statement.setInt(3, earned.tier());
            statement.setObject(4, at);
            setOptionalInstant(statement, 5, earned.availableAt());
            statement.setObject(6, at);
            statement.executeUpdate();
        }
    }

    private static void deleteCriteriaOf(Connection connection, UUID characterId, Identifier achievementId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_CRITERIA_OF_ACHIEVEMENT)) {
            statement.setObject(1, characterId);
            statement.setString(2, achievementId.toString());
            statement.executeUpdate();
        }
    }

    private static Array commissionArray(Connection connection, List<Identifier> ids) throws SQLException {
        return connection.createArrayOf("text", ids.stream().map(Identifier::toString).toArray(String[]::new));
    }

    private static Optional<StoredBoard> firstBoard(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return Optional.empty();
            }
            String[] ids = (String[]) rows.getArray(4).getArray();
            return Optional.of(new StoredBoard(
                    rows.getObject(1, LocalDate.class),
                    rows.getLong(2),
                    rows.getLong(3),
                    Arrays.stream(ids).map(Identifier::parse).toList()));
        }
    }

    private static StoredOperation storedOperation(ResultSet row) throws SQLException {
        Operation operation = new Operation(
                UUID.fromString(row.getString(1)),
                UUID.fromString(row.getString(2)),
                StorageSupport.fromStored(OperationKind.class, row.getString(3)),
                Identifier.parse(row.getString(4)),
                row.getInt(5),
                row.getLong(6),
                row.getString(7),
                CanonicalJson.of(row.getString(8)),
                row.getInt(9),
                OperationEffectCodec.fromJson(row.getString(10)),
                instant(row, 12));
        return new StoredOperation(
                operation,
                StorageSupport.fromStored(OperationStep.class, row.getString(11)),
                instant(row, 13),
                Optional.ofNullable(row.getString(14)));
    }

    private static CommissionState commission(ResultSet row) throws SQLException {
        return new CommissionState(
                Identifier.parse(row.getString(1)),
                StorageSupport.fromStored(CommissionStatus.class, row.getString(2)),
                row.getInt(3),
                instant(row, 4),
                optionalInstant(row, 5),
                optionalInstant(row, 6),
                row.getLong(7));
    }

    private static AchievementState achievement(ResultSet row) throws SQLException {
        return new AchievementState(
                Identifier.parse(row.getString(1)),
                row.getInt(2),
                row.getInt(3),
                optionalInstant(row, 4),
                optionalInstant(row, 5));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, int column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Optional<Instant> optionalInstant(ResultSet row, int column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, OffsetDateTime.class)).map(OffsetDateTime::toInstant);
    }

    private static void setOptionalInstant(PreparedStatement statement, int index, Optional<Instant> value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setObject(index, utc(value.get()));
        } else {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        }
    }

    private static void setOptionalText(PreparedStatement statement, int index, Optional<String> value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.get());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }
}
