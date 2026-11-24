package town.lampas.alina.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

class PostgresMigrationsTest {
    private static final Path DIRECTORY = Path.of("src/main/resources/alina/migrations");

    @Test
    void everySqlFileInTheDirectoryIsListedAndEveryListedFileExists() throws IOException {
        List<String> onDisk;
        try (Stream<Path> files = Files.list(DIRECTORY)) {
            onDisk = files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .sorted()
                    .toList();
        }

        assertEquals(onDisk, PostgresMigrations.FILES.stream().sorted().toList());
    }

    @Test
    void migrationsLoadInOrderFromVersionOne() {
        List<PostgresMigrations.Migration> loaded = PostgresMigrations.load();

        assertEquals(PostgresMigrations.FILES.size(), loaded.size());
        for (int index = 0; index < loaded.size(); index++) {
            PostgresMigrations.Migration migration = loaded.get(index);
            assertEquals(index + 1, migration.version());
            assertFalse(migration.sql().isBlank(), "migration " + migration.version() + " has no SQL");
        }
        assertEquals("init", loaded.get(0).description());
        assertTrue(loaded.get(0).sql().contains("CREATE TABLE character_state"));
    }

    /**
     * A schema migrated to version 1 by the earlier release, with a board row, then migrated by this one. The V1 table is
     * built by hand, because {@link PostgresStorage#migrate} would apply every migration at once.
     */
    @Nested
    @Testcontainers(disabledWithoutDocker = true)
    class V1DataUpgradedByV2 {
        private static final Identifier IMPLICIT = Identifier.parse("alina:board");
        private static final Identifier HUNTERS = Identifier.parse("alina:guild/hunters");
        private static final Identifier COURIER = Identifier.parse("alina:guild/courier");
        private static final Identifier PORTER = Identifier.parse("alina:guild/porter");
        private static final LocalDate DAY = LocalDate.parse("2026-11-05");

        @Container
        final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
                .withDatabaseName("alina_test")
                .withUsername("alina")
                .withPassword("alina");

        @Test
        void aV1BoardRowBecomesTheImplicitBoardAndBoardsOfOneDateAreIndependent() throws SQLException {
            String schema = "t" + UUID.randomUUID().toString().replace("-", "");
            seedVersionOne(schema);

            try (PostgresStorage storage = new PostgresStorage(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword(), schema, 2)) {
                storage.migrate();

                assertEquals(new StoredBoard(DAY, IMPLICIT, 3, 9, List.of(COURIER)),
                        storage.loadBoard(DAY, IMPLICIT).join().orElseThrow());
                assertEquals(Optional.empty(), storage.loadBoard(DAY, HUNTERS).join());

                StoredBoard hunters = new StoredBoard(DAY, HUNTERS, 3, 10, List.of(PORTER));
                assertEquals(hunters, storage.storeBoard(hunters).join());
                assertEquals(hunters, storage.loadBoard(DAY, HUNTERS).join().orElseThrow());
            }

            try (Connection connection = connect()) {
                assertEquals(List.of("1", "2", "3", "4", "5"), column(connection,
                        "SELECT version FROM " + schema + ".alina_schema_version ORDER BY version"));
                // The primary key of board is the date and the board id, in that order.
                assertEquals(List.of("board_date", "board_id"), column(connection,
                        "SELECT column_name FROM information_schema.key_column_usage WHERE table_schema = ? "
                                + "AND table_name = 'board' AND constraint_name = 'board_pkey' ORDER BY ordinal_position",
                        schema));
                // Every insert names its board: the column has no default after the migration.
                assertEquals(List.of("NO|"), column(connection,
                        "SELECT is_nullable || '|' || coalesce(column_default, '') FROM information_schema.columns "
                                + "WHERE table_schema = ? AND table_name = 'board' AND column_name = 'board_id'",
                        schema));
                assertThrows(SQLException.class, () -> execute(connection, "INSERT INTO " + schema + ".board "
                        + "(board_date, revision_id, seed, commission_ids) VALUES ('2026-11-06', 1, 1, '{}')"));
            }
        }

        /**
         * A schema migrated to version 3 by the release before experience markets, then migrated by this one. The market
         * tables are created, and no market row is created with them.
         */
        @Test
        void aV3SchemaGainsTheExperienceMarketTablesWithNoMarket() throws SQLException {
            String schema = "t" + UUID.randomUUID().toString().replace("-", "");
            seedThroughVersion(schema, 3);

            try (PostgresStorage storage = new PostgresStorage(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword(), schema, 2)) {
                storage.migrate();

                assertEquals(Optional.empty(), storage.loadMarket().join());
                assertEquals(List.of(), storage.loadMarketTicks(10).join());
            }

            try (Connection connection = connect()) {
                assertEquals(List.of("1", "2", "3", "4", "5"), column(connection,
                        "SELECT version FROM " + schema + ".alina_schema_version ORDER BY version"));
                assertEquals(List.of("experience_market", "experience_market_tick"), column(connection,
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = ? "
                                + "AND table_name LIKE 'experience_market%' ORDER BY table_name", schema));
            }
        }

        /** Creates the schema as version 1 left it: the version table, the V1 tables, and one board row. */
        private void seedVersionOne(String schema) throws SQLException {
            seedThroughVersion(schema, 1);
            try (Connection connection = connect()) {
                execute(connection, "INSERT INTO " + schema + ".board (board_date, revision_id, seed, commission_ids) "
                        + "VALUES ('2026-11-05', 3, 9, ARRAY['alina:guild/courier']::text[])");
            }
        }

        /**
         * Creates the schema with the migrations 1 to {@code version} applied and recorded, the way an earlier release left
         * it. The SQL is the migrations this build ships, so the version rows match what it would write.
         */
        private void seedThroughVersion(String schema, int version) throws SQLException {
            try (Connection connection = connect()) {
                execute(connection, "CREATE SCHEMA " + schema);
                execute(connection, "SET search_path TO " + schema);
                execute(connection, "CREATE TABLE alina_schema_version (version int PRIMARY KEY, "
                        + "description text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())");
                for (PostgresMigrations.Migration migration : PostgresMigrations.load().subList(0, version)) {
                    execute(connection, migration.sql());
                    try (PreparedStatement record = connection.prepareStatement(
                            "INSERT INTO alina_schema_version (version, description) VALUES (?, ?)")) {
                        record.setInt(1, migration.version());
                        record.setString(2, migration.description());
                        record.executeUpdate();
                    }
                }
            }
        }

        private Connection connect() throws SQLException {
            return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }

        private static void execute(Connection connection, String sql) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
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
    }
}
