package town.lampas.alina.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageConfigTest {
    @TempDir
    Path directory;

    @Test
    void aMissingFileIsCreatedWithMemoryDefaultsAndReadsBackTheSame() throws IOException {
        Path file = directory.resolve("config/alina.json");

        StorageConfig created = StorageConfig.loadOrCreate(file);

        assertTrue(Files.isRegularFile(file));
        assertEquals(StorageConfig.Backend.MEMORY, created.backend());
        assertEquals(4, created.poolSize());
        assertEquals("alina", created.schema());
        assertEquals("ALINA_DB_PASSWORD", created.passwordEnv());
        assertEquals(created, StorageConfig.loadOrCreate(file));
    }

    @Test
    void anEmptyFileMeansTheDefaults() {
        assertEquals(StorageConfig.DEFAULT, StorageConfig.parse("{}"));
        assertEquals(StorageConfig.DEFAULT, StorageConfig.parse("{\"storage\": {}}"));
    }

    @Test
    void postgresSettingsAreRead() {
        StorageConfig parsed = StorageConfig.parse("""
                {"storage": {
                  "type": "postgres",
                  "jdbc_url": "jdbc:postgresql://db.example:5432/alina",
                  "username": "alina_app",
                  "password_env": "MY_DB_SECRET",
                  "pool_size": 8,
                  "schema": "alina_dev"
                }}
                """);

        assertEquals(new StorageConfig(StorageConfig.Backend.POSTGRES, "jdbc:postgresql://db.example:5432/alina",
                "alina_app", "MY_DB_SECRET", 8, "alina_dev"), parsed);
    }

    @Test
    void aPasswordInTheFileIsRefusedWithoutBeingEchoed() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> StorageConfig.parse("{\"storage\": {\"type\": \"postgres\", \"password\": \"hunter2\"}}"));

        assertTrue(refused.getMessage().contains("password_env"));
        assertFalse(refused.getMessage().contains("hunter2"));
    }

    @Test
    void anUnknownKeyIsRefusedSoATypoCannotFallBackToADefault() {
        assertThrows(IllegalArgumentException.class,
                () -> StorageConfig.parse("{\"storage\": {\"pool-size\": 8}}"));
    }

    @Test
    void aMisspeltSectionNameIsRefusedSoItCannotFallBackToMemory() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> StorageConfig.parse("{\"Storage\": {\"type\": \"postgres\"}}"));

        assertTrue(refused.getMessage().contains("Storage"));
        assertThrows(IllegalArgumentException.class,
                () -> StorageConfig.parse("{\"storage \": {\"type\": \"postgres\"}}"));
    }

    @Test
    void anUnknownTypeIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> StorageConfig.parse("{\"storage\": {\"type\": \"mysql\"}}"));
    }

    @Test
    void thePostgresBackendNeedsAPostgresUrl() {
        assertThrows(IllegalArgumentException.class, () -> StorageConfig.parse(
                "{\"storage\": {\"type\": \"postgres\", \"jdbc_url\": \"jdbc:mysql://db/alina\"}}"));
    }

    @Test
    void theSchemaNameIsRestrictedBecauseItGoesIntoSql() {
        assertThrows(IllegalArgumentException.class, () -> StorageConfig.parse(
                "{\"storage\": {\"schema\": \"alina; DROP SCHEMA public\"}}"));
        assertThrows(IllegalArgumentException.class,
                () -> StorageConfig.parse("{\"storage\": {\"schema\": \"Alina\"}}"));
    }

    @Test
    void thePoolSizeIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> StorageConfig.parse("{\"storage\": {\"pool_size\": 0}}"));
        assertThrows(IllegalArgumentException.class, () -> StorageConfig.parse("{\"storage\": {\"pool_size\": 101}}"));
        assertThrows(IllegalArgumentException.class, () -> StorageConfig.parse("{\"storage\": {\"pool_size\": 2.5}}"));
    }

    @Test
    void malformedJsonIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> StorageConfig.parse("{\"storage\": "));
    }
}
