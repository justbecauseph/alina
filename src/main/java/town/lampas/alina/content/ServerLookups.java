package town.lampas.alina.content;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.ValidationContextSource;

/**
 * The running server's loot tables, tags, dimensions and reloadable registries, for the checks the bundle's registry
 * ops cannot make: loot tables and predicates are reloadable registries, outside the registry access haruhi parses
 * with. Without a server (unit tests) every check passes, and {@link #available()} says so.
 */
public final class ServerLookups {
    private static volatile MinecraftServer server;

    private ServerLookups() {}

    static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(running -> server = running);
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> server = null);
    }

    /** True while a server is running, so the existence checks below mean something. */
    public static boolean available() {
        return server != null;
    }

    /** Whether the loot table exists; true when no server is running. */
    public static boolean lootTableExists(Identifier id) {
        MinecraftServer current = server;
        if (current == null) {
            return true;
        }
        return current.reloadableRegistries().lookup().lookup(Registries.LOOT_TABLE)
                .flatMap(tables -> tables.get(ResourceKey.create(Registries.LOOT_TABLE, id)))
                .isPresent();
    }

    /** Whether the tag exists in the server's registries; true when no server is running. */
    public static <T> boolean tagExists(TagKey<T> tag) {
        MinecraftServer current = server;
        if (current == null) {
            return true;
        }
        return current.registryAccess().lookup(tag.registry()).flatMap(registry -> registry.get(tag)).isPresent();
    }

    /**
     * Whether the dimension is one the server has. Reads the dimension registry, which exists before the levels are
     * created. True when no server is running.
     */
    public static boolean dimensionExists(ResourceKey<Level> dimension) {
        MinecraftServer current = server;
        if (current == null) {
            return true;
        }
        return current.registryAccess().lookup(Registries.LEVEL_STEM)
                .flatMap(stems -> stems.get(ResourceKey.create(Registries.LEVEL_STEM, dimension.identifier())))
                .isPresent();
    }

    /**
     * The problems vanilla's own advancement loading would report for a criterion instance: loot conditions that use
     * a context the trigger does not supply, and references to predicates that do not exist. None when no server is
     * running.
     */
    public static List<String> validate(CriterionTriggerInstance instance) {
        MinecraftServer current = server;
        if (current == null) {
            return List.of();
        }
        ProblemReporter.Collector collector = new ProblemReporter.Collector();
        instance.validate(new ValidationContextSource(collector, current.reloadableRegistries().lookup()));
        List<String> problems = new ArrayList<>();
        collector.forEach((path, problem) -> problems.add(path.isEmpty()
                ? problem.description()
                : path + ": " + problem.description()));
        return problems;
    }
}
