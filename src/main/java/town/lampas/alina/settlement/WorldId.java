package town.lampas.alina.settlement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * The world id every settlement operation carries (ALINA-PLAN §7). It is a UUID in {@code alina/world_id.txt}
 * under the world folder: made on the first start and read on each start after. A file that does not hold a
 * UUID stops the start, because the operations in player data belong to the id in it.
 */
final class WorldId {
    static final String DIRECTORY = "alina";
    static final String FILE = "world_id.txt";

    private WorldId() {}

    /**
     * The id in {@code worldFolder/alina/world_id.txt}, or a new id, stored there, when the file does not exist.
     *
     * @throws IllegalStateException when the file exists but does not hold a UUID
     * @throws UncheckedIOException when the file cannot be read or written
     */
    static UUID loadOrCreate(Path worldFolder) {
        Path directory = worldFolder.resolve(DIRECTORY);
        Path file = directory.resolve(FILE);
        try {
            if (Files.exists(file)) {
                return parse(file, Files.readString(file, StandardCharsets.UTF_8));
            }
            Files.createDirectories(directory);
            UUID id = UUID.randomUUID();
            // Written beside the file and then moved over it, so a crash cannot leave a part-written id behind.
            Path temporary = directory.resolve(FILE + ".tmp");
            Files.writeString(temporary, id + "\n", StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException("could not read or write " + file, e);
        }
    }

    private static UUID parse(Path file, String text) {
        try {
            return UUID.fromString(text.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(file + " does not hold a world id (a UUID). Fix it or remove it before the "
                    + "server starts; a removed file gets a new id, and operations of the old id are then listed for "
                    + "reconciliation.", e);
        }
    }
}
