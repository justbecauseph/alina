package town.lampas.alina.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

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
}
