package town.lampas.alina.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldIdTest {
    @TempDir
    Path world;

    @Test
    void theFirstStartMakesAnIdThatLaterStartsRead() throws Exception {
        UUID first = WorldId.loadOrCreate(world);
        UUID later = WorldId.loadOrCreate(world);

        assertEquals(first, later);
        assertEquals(first.toString(), Files.readString(world.resolve("alina").resolve("world_id.txt")).strip());
    }

    @Test
    void aFileThatHoldsNoUuidStopsTheStartAndIsKept() throws Exception {
        Path file = world.resolve("alina").resolve("world_id.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not a uuid\n");

        assertThrows(IllegalStateException.class, () -> WorldId.loadOrCreate(world));
        assertEquals("not a uuid\n", Files.readString(file));
    }

    @Test
    void aNewIdLeavesNoTemporaryFileBehind() {
        WorldId.loadOrCreate(world);

        assertFalse(Files.exists(world.resolve("alina").resolve("world_id.txt.tmp")));
    }

    @Test
    void aTemporaryFileLeftByACrashIsNotReadAsTheId() throws Exception {
        Path temporary = world.resolve("alina").resolve("world_id.txt.tmp");
        Files.createDirectories(temporary.getParent());
        Files.writeString(temporary, "half written");

        UUID id = WorldId.loadOrCreate(world);

        assertEquals(id.toString(), Files.readString(world.resolve("alina").resolve("world_id.txt")).strip());
        assertFalse(Files.exists(temporary));
    }
}
