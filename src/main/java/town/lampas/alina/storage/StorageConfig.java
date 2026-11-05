package town.lampas.alina.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * The {@code storage} section of {@code config/alina.json}. The password is not part of the file: {@link #passwordEnv}
 * names the environment variable that holds it, and the file itself is refused if it carries a {@code password} key.
 *
 * @param backend which implementation to build
 * @param jdbcUrl a {@code jdbc:postgresql:} URL, read by the PostgreSQL backend only
 * @param username the database user, read by the PostgreSQL backend only
 * @param passwordEnv the environment variable holding the database password
 * @param poolSize the connection pool size, and the number of JDBC threads
 * @param schema the schema holding the Alina tables
 */
record StorageConfig(
        Backend backend, String jdbcUrl, String username, String passwordEnv, int poolSize, String schema) {

    /** The backends, named as the {@code type} key spells them. */
    enum Backend {
        MEMORY,
        POSTGRES
    }

    static final StorageConfig DEFAULT = new StorageConfig(
            Backend.MEMORY, "jdbc:postgresql://localhost:5432/alina", "alina", "ALINA_DB_PASSWORD", 4, "alina");

    static final int MAX_POOL_SIZE = 100;

    private static final Set<String> KEYS =
            Set.of("type", "jdbc_url", "username", "password_env", "pool_size", "schema");
    private static final String DEFAULT_JSON = """
            {
              "storage": {
                "type": "memory",
                "jdbc_url": "jdbc:postgresql://localhost:5432/alina",
                "username": "alina",
                "password_env": "ALINA_DB_PASSWORD",
                "pool_size": 4,
                "schema": "alina"
              }
            }
            """;

    /**
     * Reads the file. When it does not exist, writes the defaults there first and returns them.
     *
     * @throws IllegalStateException when the file cannot be read or written, or does not hold a valid configuration
     */
    static StorageConfig loadOrCreate(Path file) {
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULT_JSON, StandardCharsets.UTF_8);
                return DEFAULT;
            }
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Alina storage cannot read or write " + file, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Alina storage configuration " + file + " is invalid: " + e.getMessage(), e);
        }
    }

    /**
     * Parses the file's text. Missing keys take their defaults. Unknown keys are refused, so a misspelt key cannot
     * silently fall back to a default.
     *
     * @throws IllegalArgumentException naming the first problem found; never echoes a value that could be a secret
     */
    static StorageConfig parse(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("not valid JSON", e);
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("the file must hold a JSON object");
        }
        for (String key : root.getAsJsonObject().keySet()) {
            // A misspelt section name would otherwise select the defaults, which is the in-memory backend.
            if (!key.equals("storage")) {
                throw new IllegalArgumentException(
                        "unknown top-level key \"" + key + "\"; the only key is \"storage\"");
            }
        }
        JsonElement section = root.getAsJsonObject().get("storage");
        if (section == null) {
            return DEFAULT;
        }
        if (!section.isJsonObject()) {
            throw new IllegalArgumentException("\"storage\" must be an object");
        }
        JsonObject storage = section.getAsJsonObject();
        for (String key : storage.keySet()) {
            if (key.equals("password")) {
                throw new IllegalArgumentException(
                        "storage.password is not read from the file; set the environment variable named by "
                        + "storage.password_env");
            }
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("unknown key storage." + key);
            }
        }

        Backend backend = switch (text(storage, "type", "memory")) {
            case "memory" -> Backend.MEMORY;
            case "postgres" -> Backend.POSTGRES;
            default -> throw new IllegalArgumentException("storage.type must be \"memory\" or \"postgres\"");
        };
        String jdbcUrl = text(storage, "jdbc_url", DEFAULT.jdbcUrl());
        String username = text(storage, "username", DEFAULT.username());
        String passwordEnv = text(storage, "password_env", DEFAULT.passwordEnv());
        int poolSize = integer(storage, "pool_size", DEFAULT.poolSize());
        String schema = text(storage, "schema", DEFAULT.schema());

        if (poolSize < 1 || poolSize > MAX_POOL_SIZE) {
            throw new IllegalArgumentException(
                    "storage.pool_size must be from 1 to " + MAX_POOL_SIZE + ", found " + poolSize);
        }
        if (!StorageSupport.isSchemaName(schema)) {
            throw new IllegalArgumentException(
                    "storage.schema must be 1 to 63 lowercase letters, digits or underscores, "
                    + "not starting with a digit");
        }
        if (backend == Backend.POSTGRES) {
            if (!jdbcUrl.startsWith("jdbc:postgresql:")) {
                throw new IllegalArgumentException(
                        "storage.jdbc_url must start with jdbc:postgresql: for type postgres");
            }
            if (username.isBlank()) {
                throw new IllegalArgumentException("storage.username must not be blank for type postgres");
            }
            if (passwordEnv.isBlank()) {
                throw new IllegalArgumentException("storage.password_env must name an environment variable");
            }
        }
        return new StorageConfig(backend, jdbcUrl, username, passwordEnv, poolSize, schema);
    }

    private static String text(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("storage." + key + " must be a string");
        }
        return element.getAsString();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("storage." + key + " must be a whole number");
        }
        // Gson's getAsInt would truncate 2.5 to 2, so parse the literal text, which rejects anything but digits.
        try {
            return Integer.parseInt(element.getAsString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("storage." + key + " must be a whole number", e);
        }
    }
}
