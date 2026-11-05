package town.lampas.alina.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Applies the SQL files under {@code alina/migrations/}, in the order of {@link #FILES}. Each migration runs in its own
 * transaction together with its row in {@code alina_schema_version}. A transaction-scoped advisory lock serialises the
 * transactions, so two servers that start on one database each apply a migration once. A migration already recorded is
 * skipped, so running them again changes nothing.
 */
final class PostgresMigrations {
    /** The migration files in the order they apply. Each is named {@code V<n>__<name>.sql}, with n counting from 1. */
    static final List<String> FILES = List.of("V1__init.sql");

    /**
     * The table that records the applied versions. Named for Alina, so Alina and haruhi can share one schema: a generic
     * name would make each mod read the other's version and skip its own first migration.
     */
    static final String VERSION_TABLE = "alina_schema_version";
    private static final String DIRECTORY = "alina/migrations/";
    private static final Pattern FILE_NAME = Pattern.compile("V(\\d+)__([a-z0-9_]+)\\.sql");

    /** One migration file: its version, its description from the file name, and its SQL. */
    record Migration(int version, String description, String sql) {}

    private PostgresMigrations() {}

    static void apply(DataSource dataSource, String schema) throws SQLException {
        for (Migration migration : load()) {
            try (Connection connection = dataSource.getConnection()) {
                applyOne(connection, schema, migration);
            }
        }
    }

    /** The migration files, read from the classpath. Fails when a name does not match its place in {@link #FILES}. */
    static List<Migration> load() {
        List<Migration> migrations = new ArrayList<>();
        for (int index = 0; index < FILES.size(); index++) {
            String file = FILES.get(index);
            Matcher name = FILE_NAME.matcher(file);
            if (!name.matches() || Integer.parseInt(name.group(1)) != index + 1) {
                throw new IllegalStateException(
                        "Migration " + file + " must be named V" + (index + 1) + "__<name>.sql, in order");
            }
            migrations.add(new Migration(index + 1, name.group(2).replace('_', ' '), read(file)));
        }
        return List.copyOf(migrations);
    }

    private static void applyOne(Connection connection, String schema, Migration migration) throws SQLException {
        connection.setAutoCommit(false);
        try {
            lock(connection, schema);
            createBookkeeping(connection, schema);
            int current = currentVersion(connection);
            if (current > FILES.size()) {
                throw new SQLException("Schema " + schema + " is at version " + current
                        + ", newer than this build's " + FILES.size());
            }
            if (current < migration.version()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(migration.sql());
                }
                record(connection, migration);
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        }
    }

    private static void lock(Connection connection, String schema) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            statement.setString(1, "alina.migrations." + schema);
            statement.execute();
        }
    }

    /**
     * Creates the schema and the version table when they are missing. A check comes first because PostgreSQL tests the
     * CREATE privilege before it honours IF NOT EXISTS, so the IF NOT EXISTS form fails for a role that may use an
     * existing schema but may not create in the database. Runs under the migration lock.
     */
    private static void createBookkeeping(Connection connection, String schema) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (!exists(connection, "SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = ?", schema)) {
                statement.execute("CREATE SCHEMA \"" + schema + "\"");
            }
            if (!exists(connection, "SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n "
                    + "ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ?", schema,
                    VERSION_TABLE)) {
                statement.execute("CREATE TABLE " + VERSION_TABLE + " ("
                        + "version int PRIMARY KEY, description text NOT NULL, "
                        + "applied_at timestamptz NOT NULL DEFAULT now())");
            }
        }
    }

    private static boolean exists(Connection connection, String sql, String... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT coalesce(max(version), 0) FROM " + VERSION_TABLE)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static void record(Connection connection, Migration migration) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("INSERT INTO " + VERSION_TABLE + " (version, description) VALUES (?, ?)")) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.description());
            statement.executeUpdate();
        }
    }

    private static String read(String file) {
        String path = DIRECTORY + file;
        try (InputStream in = PostgresMigrations.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Migration resource " + path + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Migration resource " + path + " cannot be read", e);
        }
    }
}
