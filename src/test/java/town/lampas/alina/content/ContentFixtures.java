package town.lampas.alina.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import town.lampas.alina.TestBootstrap;
import town.lampas.haruhi.content.api.ExtensionContext;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.DialogueGraph;

/**
 * Shared setup for the content unit tests: Minecraft bootstrapped, registry ops over the vanilla registries (so item
 * and entity predicates parse as they do on a server), and an extension context that collects problems. No server
 * runs, so loot table and tag existence checks are skipped.
 */
final class ContentFixtures {
    static final Identifier BOARD_GRAPH = Identifier.fromNamespaceAndPath("alina", "guild/board");
    static final Identifier HUNTERS_GRAPH = Identifier.fromNamespaceAndPath("alina", "guild/hunters_board");
    static final Identifier TOWN_GRAPH = Identifier.fromNamespaceAndPath("alina", "guild/town_board");

    private static DynamicOps<JsonElement> ops;

    private ContentFixtures() {}

    static synchronized DynamicOps<JsonElement> boot() {
        if (ops == null) {
            // Registers Alina's triggers (AlinaTriggers.init) before Bootstrap freezes the registries.
            TestBootstrap.boot();
            ops = RegistryOps.create(JsonOps.INSTANCE, lookup());
        }
        return ops;
    }

    /**
     * The built-in registries themselves, plus the vanilla datapack registries. A server's registry access holds the
     * built-in registries as they are, so values parsed from them encode again; {@link VanillaRegistries#createLookup}
     * alone wraps them, and encoding a parsed holder then fails, which would hide keys the strict parse should catch.
     */
    private static HolderLookup.Provider lookup() {
        HolderLookup.Provider datapack = VanillaRegistries.createLookup();
        List<HolderLookup.RegistryLookup<?>> lookups = new ArrayList<>();
        BuiltInRegistries.REGISTRY.forEach(lookups::add);
        datapack.listRegistryKeys()
                .filter(key -> !BuiltInRegistries.REGISTRY.containsKey(key.identifier()))
                .forEach(key -> lookups.add(datapack.lookupOrThrow(key)));
        return HolderLookup.Provider.create(lookups.stream());
    }

    /** A bundle holding only the graphs the Guild boards name, as the extension parsers see it. */
    static ContentBundle bundle() {
        return new ContentBundle(ContentBundle.SCHEMA, Map.of(), Map.of(),
                Map.of(BOARD_GRAPH, new DialogueGraph(BOARD_GRAPH, "start", Map.of()),
                        HUNTERS_GRAPH, new DialogueGraph(HUNTERS_GRAPH, "start", Map.of()),
                        TOWN_GRAPH, new DialogueGraph(TOWN_GRAPH, "start", Map.of())));
    }

    /** The result of one parse and every problem it recorded. */
    record Parsed<T>(Optional<T> value, List<String> problems) {
        T orThrow() {
            if (!problems.isEmpty() || value.isEmpty()) {
                throw new AssertionError("expected a clean parse, got:\n" + String.join("\n", problems));
            }
            return value.get();
        }
    }

    static <T> Parsed<T> parse(String extension, String json, BiFunction<JsonElement, ExtensionContext, Optional<T>> reader) {
        DynamicOps<JsonElement> registryOps = boot();
        List<String> problems = new ArrayList<>();
        Identifier id = Identifier.parse(extension);
        ContentBundle bundle = bundle();
        ExtensionContext context = new ExtensionContext() {
            @Override
            public Identifier id() {
                return id;
            }

            @Override
            public ContentBundle bundle() {
                return bundle;
            }

            @Override
            public Set<Identifier> npcIds() {
                return bundle.npcs().keySet();
            }

            @Override
            public Set<Identifier> graphIds() {
                return bundle.dialogues().keySet();
            }

            @Override
            public DynamicOps<JsonElement> ops() {
                return registryOps;
            }

            @Override
            public void problem(String where, String message) {
                problems.add(where + ": " + message);
            }
        };
        Optional<T> value = reader.apply(JsonParser.parseString(json), context);
        return new Parsed<>(value, List.copyOf(problems));
    }

    static Parsed<GuildContent> guild(String json) {
        return parse("alina:guild", json, (value, context) -> GuildReader.read(value, new CountingContext(context)));
    }

    static Parsed<AchievementsContent> achievements(String json) {
        return parse("alina:achievements", json, AchievementsReader::read);
    }

    static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
