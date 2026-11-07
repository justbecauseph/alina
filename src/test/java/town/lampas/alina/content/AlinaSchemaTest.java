package town.lampas.alina.content;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Keeps {@code docs/alina.schema.json} in step with the readers: it names exactly the keys they accept, accepts the
 * documented examples inside a whole bundle, and agrees with the readers on the shape rules both check. haruhi's own
 * bundle schema, which it builds on, is read from the haruhi jar Alina builds against.
 */
class AlinaSchemaTest {
    private static final Path FILE = Path.of("docs", "alina.schema.json");
    private static final String HARUHI_DOCS = "https://raw.githubusercontent.com/justbecauseph/haruhi/main/docs/";

    private static JsonObject json;
    private static Schema schema;

    @BeforeAll
    static void load() throws IOException {
        ContentFixtures.boot();
        String text = Files.readString(FILE);
        json = JsonParser.parseString(text).getAsJsonObject();
        String haruhiSchema = haruhiSchema();
        schema = SchemaRegistry.withDialect(Dialects.getDraft202012(),
                        builder -> builder.schemaLoader(loader -> loader.resourceLoaders(
                                resources -> resources.resources(Map.of(HARUHI_DOCS + "bundle.schema.json", haruhiSchema)))))
                .getSchema(text, InputFormat.JSON);
    }

    /** haruhi's bundle schema, which the haruhi jar ships as {@code haruhi/bundle.schema.json}. */
    private static String haruhiSchema() throws IOException {
        try (InputStream in = AlinaSchemaTest.class.getClassLoader().getResourceAsStream("haruhi/bundle.schema.json")) {
            if (in == null) {
                throw new IllegalStateException("haruhi/bundle.schema.json is not on the test classpath; "
                        + "the haruhi jar ships it from 0.1.0");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> errors(JsonElement bundle) {
        return schema.validate(bundle.toString(), InputFormat.JSON).stream()
                .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
                .toList();
    }

    private static Set<String> properties(String definition) {
        return new TreeSet<>(json.getAsJsonObject("$defs").getAsJsonObject(definition).getAsJsonObject("properties").keySet());
    }

    private static Set<String> sorted(Set<String> keys) {
        return new TreeSet<>(keys);
    }

    @Test
    void schemaIsValidDraft202012() throws IOException {
        Schema meta = SchemaRegistry.withDialect(Dialects.getDraft202012())
                .getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
        List<String> errors = meta.validate(Files.readString(FILE), InputFormat.JSON).stream()
                .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
                .toList();
        assertEquals(List.of(), errors);
    }

    @Test
    void schemaNamesExactlyTheKeysTheReadersAccept() {
        assertAll(
                () -> assertEquals(sorted(GuildReader.GUILD_KEYS), properties("guild"), "guild"),
                () -> assertEquals(sorted(GuildReader.SETTINGS_KEYS), properties("settings"), "settings"),
                () -> assertEquals(sorted(GuildReader.COMMISSION_KEYS), properties("commission"), "commission"),
                () -> assertEquals(sorted(GuildReader.BOARD_KEYS), properties("board"), "board"),
                () -> assertEquals(sorted(GuildReader.REWARD_KEYS), properties("reward"), "reward"),
                () -> assertEquals(sorted(GuildReader.VOLUME_KEYS), properties("volume"), "volume"),
                () -> assertEquals(sorted(GuildReader.ITEM_ENTRY_KEYS), properties("itemEntry"), "itemEntry"),
                () -> assertEquals(sorted(AchievementsReader.ACHIEVEMENTS_KEYS), properties("achievements"), "achievements"),
                () -> assertEquals(sorted(AchievementsReader.TAB_KEYS), properties("tab"), "tab"),
                () -> assertEquals(sorted(AchievementsReader.ACHIEVEMENT_KEYS), properties("achievement"), "achievement"),
                () -> assertEquals(sorted(AchievementsReader.CRITERION_KEYS), properties("criterion"), "criterion"),
                () -> assertEquals(sorted(AchievementsReader.TIER_KEYS), properties("tier"), "tier"),
                () -> assertEquals(sorted(GuildReader.RESET_KEYS), nested("settings", "reset"), "settings.reset"),
                () -> assertEquals(sorted(AchievementsReader.REPEAT_KEYS), nested("achievement", "repeat"), "repeat"),
                () -> assertEquals(sorted(GuildReader.ITEM_PROOF_KEYS), branch("proof", "item"), "item proof"),
                () -> assertEquals(sorted(GuildReader.CRITERION_PROOF_KEYS), branch("proof", "criterion"),
                        "criterion proof"),
                () -> assertEquals(sorted(GuildReader.DESCENT_PROOF_KEYS), branch("proof", "descent"), "descent proof"),
                () -> assertEquals(sorted(GuildReader.PROOF_TYPES), enumOf("proof"), "proof types"),
                () -> assertEquals(sorted(GuildReader.BEES_CHECK_KEYS), branch("itemCheck", "alina:bees"), "bees"),
                () -> assertEquals(sorted(GuildReader.STEW_EFFECT_CHECK_KEYS), branch("itemCheck", "alina:stew_effect"),
                        "stew_effect"),
                () -> assertEquals(sorted(GuildReader.INSTRUMENT_TAG_CHECK_KEYS),
                        branch("itemCheck", "alina:instrument_tag"), "instrument_tag"));
    }

    /** The property names of an object nested under a definition's property. */
    private static Set<String> nested(String definition, String property) {
        return new TreeSet<>(json.getAsJsonObject("$defs").getAsJsonObject(definition).getAsJsonObject("properties")
                .getAsJsonObject(property).getAsJsonObject("properties").keySet());
    }

    /** The property names of the {@code allOf} branch whose {@code if} matches {@code type}. */
    private static Set<String> branch(String definition, String type) {
        for (JsonElement entry : json.getAsJsonObject("$defs").getAsJsonObject(definition).getAsJsonArray("allOf")) {
            JsonObject branch = entry.getAsJsonObject();
            String matched = branch.getAsJsonObject("if").getAsJsonObject("properties").getAsJsonObject("type")
                    .get("const").getAsString();
            if (matched.equals(type)) {
                return new TreeSet<>(branch.getAsJsonObject("then").getAsJsonObject("properties").keySet());
            }
        }
        throw new AssertionError(definition + " has no branch for " + type);
    }

    /** The values of a definition's {@code type} enum. */
    private static Set<String> enumOf(String definition) {
        Set<String> values = new TreeSet<>();
        json.getAsJsonObject("$defs").getAsJsonObject(definition).getAsJsonObject("properties").getAsJsonObject("type")
                .getAsJsonArray("enum").forEach(value -> values.add(value.getAsString()));
        return values;
    }

    /** A whole bundle holding the board graph and both documented examples. */
    private static JsonObject bundle() {
        JsonObject bundle = JsonParser.parseString("""
                { "schema": 1,
                  "dialogues": { "alina:guild/board": { "start": "hello", "nodes": { "hello": { "text": "Board." } } } } }
                """).getAsJsonObject();
        JsonObject extensions = new JsonObject();
        extensions.add("alina:guild", JsonParser.parseString(GuildReaderTest.EXAMPLE));
        extensions.add("alina:achievements", JsonParser.parseString(AchievementsReaderTest.EXAMPLE));
        bundle.add("extensions", extensions);
        return bundle;
    }

    @Test
    void schemaAcceptsTheDocumentedExamples() {
        assertEquals(List.of(), errors(bundle()));
    }

    @Test
    void haruhisRulesStillApply() {
        JsonObject bundle = bundle();
        bundle.addProperty("schema", 2);
        assertFalse(errors(bundle).isEmpty(), "the schema accepts a bundle with schema 2");
    }

    private static JsonObject guild(JsonObject bundle) {
        return bundle.getAsJsonObject("extensions").getAsJsonObject("alina:guild");
    }

    private static JsonObject commission(JsonObject bundle, String id) {
        return guild(bundle).getAsJsonObject("commissions").getAsJsonObject(id);
    }

    private static JsonObject achievement(JsonObject bundle, String id) {
        return bundle.getAsJsonObject("extensions").getAsJsonObject("alina:achievements")
                .getAsJsonObject("achievements").getAsJsonObject(id);
    }

    /** Changes to the examples that the format refuses. */
    static Stream<Arguments> refusedShapes() {
        return Stream.of(
                Arguments.of("a feat with a cooldown", (Consumer<JsonObject>) b ->
                        commission(b, "alina:guild/e1_longshot").addProperty("cooldown", "PT1H")),
                Arguments.of("an unknown commission key", (Consumer<JsonObject>) b ->
                        commission(b, "alina:guild/e1_longshot").addProperty("only_if", true)),
                Arguments.of("category G", (Consumer<JsonObject>) b ->
                        commission(b, "alina:guild/e1_longshot").addProperty("category", "G")),
                Arguments.of("an item proof with all_of and any_of", (Consumer<JsonObject>) b -> {
                    JsonObject proof = commission(b, "alina:guild/b3_screaming_horn").getAsJsonObject("proof");
                    proof.add("all_of", proof.get("any_of").deepCopy());
                }),
                Arguments.of("a count inside an item predicate", (Consumer<JsonObject>) b ->
                        commission(b, "alina:guild/b3_screaming_horn").getAsJsonObject("proof").getAsJsonArray("any_of")
                                .get(0).getAsJsonObject().getAsJsonObject("item").addProperty("count", 2)),
                Arguments.of("no settings", (Consumer<JsonObject>) b -> guild(b).remove("settings")),
                Arguments.of("no board_graph without boards", (Consumer<JsonObject>) b ->
                        guild(b).getAsJsonObject("settings").remove("board_graph")),
                Arguments.of("a volume with two coordinates", (Consumer<JsonObject>) b ->
                        guild(b).getAsJsonObject("volumes").getAsJsonObject("alina:guild/high_dive")
                                .add("from", JsonParser.parseString("[0, 1]"))),
                Arguments.of("tiers on two criteria", (Consumer<JsonObject>) b ->
                        achievement(b, "alina:guild/zombie_slayer").getAsJsonObject("criteria")
                                .add("more", JsonParser.parseString("{ \"trigger\": \"minecraft:tame_animal\" }"))),
                Arguments.of("an unknown achievement key", (Consumer<JsonObject>) b ->
                        achievement(b, "alina:guild/zombie_slayer").addProperty("parnet", "x")),
                Arguments.of("frame legendary", (Consumer<JsonObject>) b ->
                        achievement(b, "alina:guild/zombie_slayer").addProperty("frame", "legendary")),
                Arguments.of("a zero repeat cooldown", (Consumer<JsonObject>) b ->
                        achievement(b, "alina:guild/zombie_slayer").getAsJsonObject("repeat").addProperty("cooldown", "PT0S")),
                Arguments.of("a zero default cooldown", (Consumer<JsonObject>) b ->
                        guild(b).getAsJsonObject("settings").addProperty("default_cooldown", "P0D")),
                Arguments.of("a count on a tiered criterion", (Consumer<JsonObject>) b ->
                        achievement(b, "alina:guild/zombie_slayer").getAsJsonObject("criteria").getAsJsonObject("kills")
                                .addProperty("count", 3)),
                Arguments.of("a background written as a full texture path", (Consumer<JsonObject>) b ->
                        b.getAsJsonObject("extensions").getAsJsonObject("alina:achievements").getAsJsonObject("tabs")
                                .getAsJsonObject("alina:guild")
                                .addProperty("background", "minecraft:textures/block/spruce_planks.png")),
                Arguments.of("an icon count of 0", (Consumer<JsonObject>) b ->
                        achievement(b, "alina:guild/zombie_slayer").getAsJsonObject("icon").addProperty("count", 0)),
                Arguments.of("a proof without a type", (Consumer<JsonObject>) b ->
                        commission(b, "alina:guild/e3_descent").getAsJsonObject("proof").remove("type")));
    }

    /** Changes to the examples that the format allows. */
    static Stream<Arguments> allowedShapes() {
        return Stream.of(
                Arguments.of("an icon written as an item id", (Consumer<JsonObject>) b ->
                        achievement(b, "alina:guild/zombie_slayer").addProperty("icon", "minecraft:zombie_head")),
                Arguments.of("an icon with a count of 2", (Consumer<JsonObject>) b ->
                        achievement(b, "alina:guild/zombie_slayer").getAsJsonObject("icon").addProperty("count", 2)),
                Arguments.of("a board_reset cooldown", (Consumer<JsonObject>) b ->
                        commission(b, "alina:guild/b3_screaming_horn").addProperty("cooldown", "board_reset")),
                Arguments.of("a weight and in_rotation", (Consumer<JsonObject>) b -> {
                    commission(b, "alina:guild/b3_screaming_horn").addProperty("weight", 3);
                    commission(b, "alina:guild/b3_screaming_horn").addProperty("in_rotation", false);
                }),
                Arguments.of("a bees check as bounds", (Consumer<JsonObject>) b ->
                        commission(b, "alina:guild/b3_screaming_horn").getAsJsonObject("proof").getAsJsonArray("any_of")
                                .get(0).getAsJsonObject().add("checks", JsonParser.parseString(
                                        "[ { \"type\": \"alina:bees\", \"count\": { \"min\": 3 } } ]"))),
                Arguments.of("an all_of proof with consume false", (Consumer<JsonObject>) b -> {
                    JsonObject proof = commission(b, "alina:guild/b3_screaming_horn").getAsJsonObject("proof");
                    proof.add("all_of", proof.remove("any_of"));
                    proof.getAsJsonArray("all_of").get(0).getAsJsonObject().addProperty("consume", false);
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allowedShapes")
    void schemaAndReadersBothAllow(String name, Consumer<JsonObject> change) {
        JsonObject bundle = bundle();
        change.accept(bundle);
        List<String> readerProblems = Stream.concat(
                        ContentFixtures.guild(bundle.getAsJsonObject("extensions").get("alina:guild").toString())
                                .problems().stream(),
                        ContentFixtures.achievements(
                                        bundle.getAsJsonObject("extensions").get("alina:achievements").toString())
                                .problems().stream())
                .toList();
        assertAll(
                () -> assertEquals(List.of(), readerProblems, "the readers refuse it"),
                () -> assertEquals(List.of(), errors(bundle), "the schema refuses it"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedShapes")
    void schemaAndReadersBothRefuse(String name, Consumer<JsonObject> change) {
        JsonObject bundle = bundle();
        change.accept(bundle);
        JsonElement guildSection = bundle.getAsJsonObject("extensions").get("alina:guild");
        JsonElement achievementsSection = bundle.getAsJsonObject("extensions").get("alina:achievements");
        List<String> readerProblems = Stream.concat(
                        ContentFixtures.guild(guildSection.toString()).problems().stream(),
                        ContentFixtures.achievements(achievementsSection.toString()).problems().stream())
                .toList();
        assertAll(
                () -> assertFalse(readerProblems.isEmpty(), "the readers accept it"),
                () -> assertFalse(errors(bundle).isEmpty(), "the schema accepts it"));
    }

    /** The whole bundle with the boards example as its alina:guild section. */
    private static JsonObject boardsBundle() {
        JsonObject bundle = bundle();
        bundle.getAsJsonObject("extensions").add("alina:guild", JsonParser.parseString(GuildReaderTest.BOARDS));
        return bundle;
    }

    private static JsonObject board(JsonObject bundle, String id) {
        return guild(bundle).getAsJsonObject("boards").getAsJsonObject(id);
    }

    /** What the readers report for both sections of the bundle. */
    private static List<String> readerProblems(JsonObject bundle) {
        return Stream.concat(
                        ContentFixtures.guild(bundle.getAsJsonObject("extensions").get("alina:guild").toString())
                                .problems().stream(),
                        ContentFixtures.achievements(
                                        bundle.getAsJsonObject("extensions").get("alina:achievements").toString())
                                .problems().stream())
                .toList();
    }

    @Test
    void schemaAndReadersBothAcceptTheBoardsExample() {
        JsonObject bundle = boardsBundle();
        assertAll(
                () -> assertEquals(List.of(), readerProblems(bundle), "the readers refuse it"),
                () -> assertEquals(List.of(), errors(bundle), "the schema refuses it"));
    }

    /** Changes to the boards example that the format allows. The pools stay whole: each commission is in one board. */
    static Stream<Arguments> allowedBoardShapes() {
        return Stream.of(
                Arguments.of("a board without a name", (Consumer<JsonObject>) b ->
                        board(b, "alina:guild/hunters").remove("name")),
                Arguments.of("a name as a text component", (Consumer<JsonObject>) b ->
                        board(b, "alina:guild/hunters").add("name", JsonParser.parseString("{ \"text\": \"Hunters\" }"))),
                Arguments.of("a size of 1 and a reach of 64", (Consumer<JsonObject>) b -> {
                    board(b, "alina:guild/hunters").addProperty("size", 1);
                    board(b, "alina:guild/hunters").addProperty("reach", 64);
                }),
                Arguments.of("an empty categories list", (Consumer<JsonObject>) b ->
                        board(b, "alina:guild/town").add("categories", JsonParser.parseString("[]"))),
                Arguments.of("a commission named and in the category", (Consumer<JsonObject>) b ->
                        board(b, "alina:guild/town").add("categories", JsonParser.parseString("[\"B\"]"))));
    }

    /** Changes to the boards example that the format refuses. */
    static Stream<Arguments> refusedBoardShapes() {
        return Stream.of(
                Arguments.of("board_graph beside boards", (Consumer<JsonObject>) b ->
                        guild(b).getAsJsonObject("settings").addProperty("board_graph", "alina:guild/board")),
                Arguments.of("a board without a graph", (Consumer<JsonObject>) b ->
                        board(b, "alina:guild/hunters").remove("graph")),
                Arguments.of("a board size of 0", (Consumer<JsonObject>) b ->
                        board(b, "alina:guild/hunters").addProperty("size", 0)),
                Arguments.of("a board reach of 0", (Consumer<JsonObject>) b ->
                        board(b, "alina:guild/hunters").addProperty("reach", 0)),
                Arguments.of("a board reach of 65", (Consumer<JsonObject>) b ->
                        board(b, "alina:guild/hunters").addProperty("reach", 65)),
                Arguments.of("a board category G", (Consumer<JsonObject>) b ->
                        board(b, "alina:guild/hunters").add("categories", JsonParser.parseString("[\"G\"]"))),
                Arguments.of("categories that are not a list", (Consumer<JsonObject>) b ->
                        board(b, "alina:guild/hunters").add("categories", JsonParser.parseString("\"E\""))),
                Arguments.of("a board commission that is not an id", (Consumer<JsonObject>) b ->
                        board(b, "alina:guild/town").add("commissions", JsonParser.parseString("[7]"))),
                Arguments.of("an unknown board key", (Consumer<JsonObject>) b ->
                        board(b, "alina:guild/hunters").addProperty("only_if", true)),
                Arguments.of("no boards", (Consumer<JsonObject>) b -> guild(b).add("boards", new JsonObject())),
                Arguments.of("a board that is not an object", (Consumer<JsonObject>) b ->
                        guild(b).getAsJsonObject("boards").addProperty("alina:guild/town", "x")),
                Arguments.of("a boards key that is not an id", (Consumer<JsonObject>) b ->
                        guild(b).getAsJsonObject("boards").add("Bad Key", board(b, "alina:guild/town").deepCopy())),
                Arguments.of("boards that are not an object", (Consumer<JsonObject>) b ->
                        guild(b).add("boards", JsonParser.parseString("[]"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allowedBoardShapes")
    void boardsSchemaAndReadersBothAllow(String name, Consumer<JsonObject> change) {
        JsonObject bundle = boardsBundle();
        change.accept(bundle);
        assertAll(
                () -> assertEquals(List.of(), readerProblems(bundle), "the readers refuse it"),
                () -> assertEquals(List.of(), errors(bundle), "the schema refuses it"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedBoardShapes")
    void boardsSchemaAndReadersBothRefuse(String name, Consumer<JsonObject> change) {
        JsonObject bundle = boardsBundle();
        change.accept(bundle);
        assertAll(
                () -> assertFalse(readerProblems(bundle).isEmpty(), "the readers accept it"),
                () -> assertFalse(errors(bundle).isEmpty(), "the schema accepts it"));
    }

    @Test
    void readersAcceptTheExamples() {
        assertTrue(ContentFixtures.guild(GuildReaderTest.EXAMPLE).problems().isEmpty());
        assertTrue(ContentFixtures.achievements(AchievementsReaderTest.EXAMPLE).problems().isEmpty());
    }
}
