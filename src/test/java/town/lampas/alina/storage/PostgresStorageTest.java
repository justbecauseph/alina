package town.lampas.alina.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the storage contract against {@link PostgresStorage} on a PostgreSQL container, plus checks that only the
 * database can show: migrations, the schema they land in, privileges, the column types, locking between sessions, and
 * a query that gets no answer. Skipped when Docker is not running.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresStorageTest extends AlinaStorageContractTest {
    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-05T10:01:00Z");
    private static final Instant T3 = Instant.parse("2026-11-05T10:03:00Z");
    private static final UUID ALICE = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final Identifier COURIER = Identifier.parse("alina:guild/courier");
    private static final String ROLE_PASSWORD = "secret";
    /** Longer than any socket timeout the storage sets, so a read that gets an answer finishes first. */
    private static final long NO_ANSWER_LIMIT_SECONDS = 30;
    private static final long LOCK_WAIT_LIMIT_SECONDS = 10;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("alina_test")
            .withUsername("alina")
            .withPassword("alina");

    @Override
    protected PostgresStorage createStorage() {
        PostgresStorage storage = open(newSchema());
        storage.migrate();
        return storage;
    }

    @Test
    void migrationsCreateTheTablesInTheConfiguredSchemaAndRunOnce() throws SQLException {
        String schema = newSchema();
        try (PostgresStorage storage = open(schema)) {
            storage.migrate();
            storage.migrate();
        }

        try (Connection connection = connect()) {
            assertEquals(List.of("achievement", "alina_schema_version", "board", "character_state", "commission_state",
                            "criterion_progress", "operation"),
                    column(connection, "SELECT table_name FROM information_schema.tables "
                            + "WHERE table_schema = ? ORDER BY table_name", schema));
            assertEquals(List.of("1"),
                    column(connection, "SELECT version FROM " + schema + ".alina_schema_version ORDER BY version"));
            assertEquals(List.of("jsonb", "jsonb"), column(connection, "SELECT data_type FROM information_schema.columns "
                    + "WHERE table_schema = ? AND table_name = 'operation' AND column_name IN ('reward', 'effect') "
                    + "ORDER BY column_name", schema));
        }
    }

    @Test
    void aRoleThatOwnsTheSchemaCanMigrateItWithoutCreateOnTheDatabase() throws SQLException {
        String schema = newSchema();
        String role = newRole();
        try (Connection admin = connect()) {
            execute(admin, "CREATE ROLE " + role + " LOGIN PASSWORD '" + ROLE_PASSWORD + "'");
            execute(admin, "CREATE SCHEMA " + schema + " AUTHORIZATION " + role);
            assertFalse(holds(admin, "SELECT has_database_privilege(?, current_database(), 'CREATE')", role));
        }

        try (PostgresStorage first = open(schema, role, ROLE_PASSWORD)) {
            first.migrate();
        }
        try (PostgresStorage second = open(schema, role, ROLE_PASSWORD)) {
            second.migrate();
        }

        try (Connection connection = connect()) {
            assertEquals(List.of("1"),
                    column(connection, "SELECT version FROM " + schema + ".alina_schema_version ORDER BY version"));
        }
    }

    /** haruhi keeps its own schema_version table in a shared schema. Alina must migrate there, not skip its V1. */
    @Test
    void aSchemaThatHoldsAnotherModsVersionTableStillGetsAlinasTables() throws SQLException {
        String schema = newSchema();
        try (Connection admin = connect()) {
            execute(admin, "CREATE SCHEMA " + schema);
            execute(admin, "CREATE TABLE " + schema + ".schema_version (version int PRIMARY KEY, "
                    + "description text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())");
            execute(admin, "INSERT INTO " + schema + ".schema_version (version, description) VALUES (1, 'other mod')");
        }

        try (PostgresStorage storage = open(schema)) {
            storage.migrate();
            await(storage.setReputation(ALICE, 2, T0));
            assertEquals(2, await(storage.loadCharacter(ALICE)).reputation());
        }

        try (Connection connection = connect()) {
            assertEquals(List.of("1"),
                    column(connection, "SELECT version FROM " + schema + ".alina_schema_version ORDER BY version"));
            assertEquals(List.of("other mod"),
                    column(connection, "SELECT description FROM " + schema + ".schema_version ORDER BY version"));
        }
    }

    @Test
    void aRoleWithNoCreateRightsRestartsAgainstAMigratedSchema() throws SQLException {
        String schema = newSchema();
        String role = newRole();
        try (PostgresStorage owner = open(schema)) {
            owner.migrate();
        }
        try (Connection admin = connect()) {
            execute(admin, "CREATE ROLE " + role + " LOGIN PASSWORD '" + ROLE_PASSWORD + "'");
            execute(admin, "GRANT USAGE ON SCHEMA " + schema + " TO " + role);
            execute(admin, "GRANT SELECT ON " + schema + ".alina_schema_version TO " + role);
            assertFalse(holds(admin, "SELECT has_schema_privilege(?, ?, 'CREATE')", role, schema));
            assertFalse(holds(admin, "SELECT has_database_privilege(?, current_database(), 'CREATE')", role));
        }

        try (PostgresStorage restart = open(schema, role, ROLE_PASSWORD)) {
            restart.migrate();
        }
    }

    @Test
    void stateWrittenBeforeARestartIsReadAfterIt() {
        String schema = newSchema();
        try (PostgresStorage first = open(schema)) {
            first.migrate();
            await(first.setReputation(ALICE, 2, T0));
            await(first.acceptCommission(ALICE, COURIER, 1, T0));
            await(first.applyOperation(new Operation(UUID.randomUUID(), ALICE, OperationKind.TURN_IN, COURIER, 0, 9,
                    "minecraft:overworld", "{\"coins\":5}", 4,
                    new OperationEffect.CommissionSettled(COURIER, CommissionStatus.COOLDOWN, Optional.of(T3)), T1)));
        }

        try (PostgresStorage second = open(schema)) {
            second.migrate();
            CharacterState state = await(second.loadCharacter(ALICE));
            assertEquals(6, state.reputation());
            assertEquals(Map.of(COURIER, new CommissionState(COURIER, CommissionStatus.COOLDOWN, 0, T0,
                    Optional.of(T1), Optional.of(T3), 1)), state.commissions());
        }
    }

    @Test
    void twoServersApplyingTheSameOperationAtOnceAppliesItOnce() throws Exception {
        String schema = newSchema();
        Operation turnIn = new Operation(UUID.fromString("55555555-5555-4555-8555-555555555555"), ALICE,
                OperationKind.TURN_IN, COURIER, 0, 1, "minecraft:overworld", "{\"coins\":5}", 4,
                new OperationEffect.CommissionSettled(COURIER, CommissionStatus.COOLDOWN, Optional.of(T3)), T1);
        try (PostgresStorage setup = open(schema);
                PostgresStorage serverA = openWithOneConnection(schema);
                PostgresStorage serverB = openWithOneConnection(schema)) {
            setup.migrate();

            try (Connection blocker = connect()) {
                blocker.setAutoCommit(false);
                // Holds the character row's table, so both applications reach their first write and wait behind it.
                execute(blocker, "LOCK TABLE " + schema + ".character_state IN EXCLUSIVE MODE");
                CompletableFuture<ApplyResult> first = serverA.applyOperation(turnIn);
                CompletableFuture<ApplyResult> second = serverB.applyOperation(turnIn);
                awaitBlockedOnLocks(blocker, 2);
                blocker.commit();

                ApplyResult firstResult = await(first);
                ApplyResult secondResult = await(second);
                assertEquals(1, (firstResult.fresh() ? 1 : 0) + (secondResult.fresh() ? 1 : 0));
            }

            assertEquals(4, await(setup.loadCharacter(ALICE)).reputation());
        }
    }

    @Test
    void aQueryThatGetsNoAnswerFailsInsteadOfHangingItsCaller() throws SQLException {
        String schema = newSchema();
        try (PostgresStorage storage = open(schema)) {
            storage.migrate();
            try (Connection blocker = connect()) {
                blocker.setAutoCommit(false);
                // The read waits behind this lock until the server's lock_timeout fails it, so the caller gets an
                // error.
                execute(blocker, "LOCK TABLE " + schema + ".character_state IN ACCESS EXCLUSIVE MODE");
                CompletableFuture<CharacterState> read = storage.loadCharacter(UUID.randomUUID());

                CompletionException failure = assertThrows(CompletionException.class,
                        () -> read.orTimeout(NO_ANSWER_LIMIT_SECONDS, TimeUnit.SECONDS).join());

                assertFalse(failure.getCause() instanceof TimeoutException,
                        "the read was still waiting after " + NO_ANSWER_LIMIT_SECONDS + " seconds");
                assertInstanceOf(SQLException.class, failure.getCause());
                blocker.rollback();
            }
        }
    }

    /**
     * An autocommit write that waits on a lock must fail on the server with its lock timeout (SQLState 55P03) before
     * the client's socket read fails (08006). A client-side failure would leave the statement queued on the server, and
     * it would commit once the lock is released.
     */
    @Test
    void anAutocommitWriteThatWaitsOnALockFailsAndDoesNotCommitWhenTheLockIsReleased() throws Exception {
        String schema = newSchema();
        try (PostgresStorage storage = open(schema)) {
            storage.migrate();
            await(storage.acceptCommission(ALICE, COURIER, 1, T0));
            try (Connection blocker = connect()) {
                blocker.setAutoCommit(false);
                execute(blocker, "LOCK TABLE " + schema + ".commission_state IN ACCESS EXCLUSIVE MODE");
                CompletableFuture<Boolean> abandon = storage.abandonCommission(ALICE, COURIER);

                CompletionException failure = assertThrows(CompletionException.class,
                        () -> abandon.orTimeout(NO_ANSWER_LIMIT_SECONDS, TimeUnit.SECONDS).join());

                assertFalse(failure.getCause() instanceof TimeoutException,
                        "the write was still waiting after " + NO_ANSWER_LIMIT_SECONDS + " seconds");
                assertEquals("55P03", assertInstanceOf(SQLException.class, failure.getCause()).getSQLState());
                blocker.rollback();
                awaitNoRunningStatementOn(blocker, "commission_state");
            }

            assertEquals(
                    new CommissionState(COURIER, CommissionStatus.ACTIVE, 0, T0, Optional.empty(), Optional.empty(), 1),
                    await(storage.loadCharacter(ALICE)).commissions().get(COURIER));
        }
    }

    @Test
    void theDatabaseRefusesACommissionRowWhoseCooldownContradictsItsStatus() throws SQLException {
        String schema = newSchema();
        try (PostgresStorage storage = open(schema)) {
            storage.migrate();
        }

        try (Connection connection = connect()) {
            SQLException cooldownWithoutEnd = assertThrows(SQLException.class,
                    () -> insertCommission(connection, schema, "alina:guild/courier", "cooldown", null));
            SQLException completedWithEnd = assertThrows(SQLException.class,
                    () -> insertCommission(connection, schema, "alina:guild/scribe", "completed", T3));
            assertEquals("23514", cooldownWithoutEnd.getSQLState());
            assertEquals("23514", completedWithEnd.getSQLState());

            insertCommission(connection, schema, "alina:guild/porter", "cooldown", T3);
            insertCommission(connection, schema, "alina:guild/unlisted", "completed", null);
            assertEquals(List.of("alina:guild/porter", "alina:guild/unlisted"), column(connection,
                    "SELECT commission_id FROM " + schema + ".commission_state ORDER BY commission_id"));
        }
    }

    private static PostgresStorage open(String schema) {
        return open(schema, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static PostgresStorage open(String schema, String username, String password) {
        return new PostgresStorage(POSTGRES.getJdbcUrl(), username, password, schema, 2);
    }

    /** A storage whose one connection exists as soon as it is built, as a separate server's would. */
    private static PostgresStorage openWithOneConnection(String schema) {
        return new PostgresStorage(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), schema, 1);
    }

    private static String newSchema() {
        return "t" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String newRole() {
        return "r" + UUID.randomUUID().toString().replace("-", "");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** The first column of the single row a query returns, as a boolean. */
    private static boolean holds(Connection connection, String sql, String... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    /** The first column of each row, as text. */
    private static List<String> column(Connection connection, String sql, String... parameters) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        return values;
    }

    /** Waits until {@code count} sessions of this database are blocked on a lock, so the test knows they queued. */
    private static void awaitBlockedOnLocks(Connection observer, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_WAIT_LIMIT_SECONDS);
        int blocked = blockedOnLocks(observer);
        while (blocked < count) {
            if (System.nanoTime() > deadline) {
                fail("only " + blocked + " sessions were blocked on a lock; expected " + count + ". Sessions: "
                        + sessions(observer));
            }
            Thread.sleep(20);
            blocked = blockedOnLocks(observer);
        }
    }

    private static int blockedOnLocks(Connection observer) throws SQLException {
        try (Statement statement = observer.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM pg_stat_activity "
                        + "WHERE datname = current_database() AND wait_event_type = 'Lock'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /**
     * Waits until no other session of this database is running a statement on the table. A write that was still queued
     * behind a lock has then finished, one way or the other.
     */
    private static void awaitNoRunningStatementOn(Connection observer, String table) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_WAIT_LIMIT_SECONDS);
        while (runningStatementsOn(observer, table) > 0) {
            if (System.nanoTime() > deadline) {
                fail("a statement on " + table + " is still running. Sessions: " + sessions(observer));
            }
            Thread.sleep(20);
        }
    }

    private static int runningStatementsOn(Connection observer, String table) throws SQLException {
        try (PreparedStatement statement = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity "
                + "WHERE datname = current_database() AND pid <> pg_backend_pid() AND state = 'active' "
                + "AND query LIKE ?")) {
            statement.setString(1, "%" + table + "%");
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /** Inserts a commission row for ALICE, bypassing the storage, so the table's own rules are what refuses it. */
    private static void insertCommission(Connection connection, String schema, String commission, String status,
            Instant cooldownUntil) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + schema + ".commission_state "
                + "(character_id, commission_id, status, progress, accepted_at, cooldown_until, revision_id) "
                + "VALUES (?, ?, ?, 0, ?, ?, 1)")) {
            insert.setObject(1, ALICE);
            insert.setString(2, commission);
            insert.setString(3, status);
            insert.setObject(4, OffsetDateTime.ofInstant(T0, ZoneOffset.UTC));
            if (cooldownUntil == null) {
                insert.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                insert.setObject(5, OffsetDateTime.ofInstant(cooldownUntil, ZoneOffset.UTC));
            }
            insert.executeUpdate();
        }
    }

    private static String sessions(Connection observer) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Statement statement = observer.createStatement();
                ResultSet rows = statement.executeQuery("SELECT pid, state, wait_event_type, wait_event, "
                        + "left(query, 60) FROM pg_stat_activity WHERE datname = current_database()")) {
            while (rows.next()) {
                lines.add(rows.getInt(1) + " " + rows.getString(2) + " " + rows.getString(3) + "/"
                        + rows.getString(4) + " " + rows.getString(5));
            }
        }
        return String.join(" | ", lines);
    }
}
