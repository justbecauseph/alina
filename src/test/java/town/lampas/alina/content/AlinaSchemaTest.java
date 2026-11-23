package town.lampas.alina.content;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
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
                        branch("itemCheck", "alina:instrument_tag"), "instrument_tag"),
                () -> assertEquals(sorted(TradeReader.TRADE_KEYS), properties("trade"), "trade"),
                () -> assertEquals(sorted(TradeReader.DENOMINATION_KEYS), properties("denomination"), "denomination"),
                () -> assertEquals(sorted(TradeReader.PRICE_LIST_KEYS), properties("priceList"), "priceList"),
                () -> assertEquals(sorted(TradeReader.SELL_RULE_KEYS), properties("sellRule"), "sellRule"),
                () -> assertEquals(sorted(TradeReader.PRICE_KEYS), properties("price"), "price"),
                () -> assertEquals(sorted(TradeReader.SHOP_KEYS), properties("shop"), "shop"),
                () -> assertEquals(sorted(TradeReader.SELL_KEYS), properties("sell"), "sell"),
                () -> assertEquals(sorted(TradeReader.OFFER_KEYS), properties("offer"), "offer"),
                () -> assertEquals(sorted(TradeReader.LIMIT_KEYS), properties("limit"), "limit"),
                () -> assertEquals(sorted(TradeReader.REQUIRES_KEYS), properties("requires"), "requires"),
                () -> assertEquals(sorted(TradeReader.EXPERIENCE_KEYS), properties("experience"), "experience"),
                () -> assertEquals(sorted(TradeReader.RATE_KEYS), properties("rate"), "rate"),
                () -> assertEquals(sorted(TradeReader.FEE_KEYS), properties("fee"), "fee"),
                () -> assertEquals(sorted(TradeReader.EXPERIENCE_MARKET_KEYS), properties("experienceMarket"),
                        "experienceMarket"),
                () -> assertEquals(sorted(TradeReader.RESERVE_KEYS), nested("experienceMarket", "reserve"), "reserve"),
                () -> assertEquals(sorted(TradeReader.LINE_KEYS), properties("shopLines"), "shopLines"));
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

    /** A whole bundle holding the board graph and the three documented examples. */
    private static JsonObject bundle() {
        JsonObject bundle = JsonParser.parseString("""
                { "schema": 1,
                  "dialogues": { "alina:guild/board": { "start": "hello", "nodes": { "hello": { "text": "Board." } } } } }
                """).getAsJsonObject();
        JsonObject extensions = new JsonObject();
        extensions.add("alina:guild", JsonParser.parseString(GuildReaderTest.EXAMPLE));
        extensions.add("alina:achievements", JsonParser.parseString(AchievementsReaderTest.EXAMPLE));
        extensions.add("alina:trade", JsonParser.parseString(TradeReaderTest.EXAMPLE));
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

    private static JsonObject tradeSection(JsonObject bundle) {
        return bundle.getAsJsonObject("extensions").getAsJsonObject("alina:trade");
    }

    private static JsonObject shop(JsonObject bundle, String id) {
        return tradeSection(bundle).getAsJsonObject("shops").getAsJsonObject(id);
    }

    /** The offer at {@code index} of the shop's buy list. */
    private static JsonObject offer(JsonObject bundle, String shop, int index) {
        return shop(bundle, shop).getAsJsonArray("buy").get(index).getAsJsonObject();
    }

    /** The first rule of the price list lampas:common_drops. */
    private static JsonObject commonDropsRule(JsonObject bundle, int index) {
        return tradeSection(bundle).getAsJsonObject("price_lists").getAsJsonObject("lampas:common_drops")
                .getAsJsonArray("rules").get(index).getAsJsonObject();
    }

    private static JsonObject market(JsonObject bundle) {
        return tradeSection(bundle).getAsJsonObject("experience_market");
    }

    private static JsonObject reserve(JsonObject bundle) {
        return market(bundle).getAsJsonObject("reserve");
    }

    /** The bundle's trade section takes the experience market of the example, and Mira's experience as the example's fees. */
    private static void useMarket(JsonObject bundle) {
        JsonObject example = JsonParser.parseString(TradeReaderTest.MARKET_EXAMPLE).getAsJsonObject();
        tradeSection(bundle).add("experience_market", example.get("experience_market"));
        shop(bundle, "lampas:mira").add("experience",
                example.getAsJsonObject("shops").getAsJsonObject("lampas:mira").get("experience"));
    }

    /** Mira's experience of the given JSON text. */
    private static void miraExperience(JsonObject bundle, String experience) {
        shop(bundle, "lampas:mira").add("experience", JsonParser.parseString(experience));
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
        assertAll(
                () -> assertEquals(List.of(), readerProblems(bundle), "the readers refuse it"),
                () -> assertEquals(List.of(), errors(bundle), "the schema refuses it"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedShapes")
    void schemaAndReadersBothRefuse(String name, Consumer<JsonObject> change) {
        JsonObject bundle = bundle();
        change.accept(bundle);
        assertAll(
                () -> assertFalse(readerProblems(bundle).isEmpty(), "the readers accept it"),
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

    /** What the readers report for the three sections of the bundle. */
    private static List<String> readerProblems(JsonObject bundle) {
        JsonObject extensions = bundle.getAsJsonObject("extensions");
        return Stream.of(
                        ContentFixtures.guild(extensions.get("alina:guild").toString()).problems(),
                        ContentFixtures.achievements(extensions.get("alina:achievements").toString()).problems(),
                        ContentFixtures.trade(extensions.get("alina:trade").toString()).problems())
                .flatMap(List::stream)
                .toList();
    }

    @Test
    void schemaAndReadersBothAcceptTheExperienceMarketExample() {
        JsonObject bundle = bundle();
        bundle.getAsJsonObject("extensions").add("alina:trade", JsonParser.parseString(TradeReaderTest.MARKET_EXAMPLE));
        assertAll(
                () -> assertEquals(List.of(), readerProblems(bundle), "the readers refuse it"),
                () -> assertEquals(List.of(), errors(bundle), "the schema refuses it"));
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
        assertTrue(ContentFixtures.trade(TradeReaderTest.EXAMPLE).problems().isEmpty());
    }

    /** Changes to the trade example that the format allows. The readers and the schema must both take them. */
    static Stream<Arguments> allowedTradeShapes() {
        return Stream.of(
                Arguments.of("a shop with experience buy only", (Consumer<JsonObject>) b -> {
                    shop(b, "lampas:mira").remove("sell");
                    shop(b, "lampas:mira").remove("buy");
                    shop(b, "lampas:mira").getAsJsonObject("experience").remove("sell");
                }),
                Arguments.of("a shop name as a text component", (Consumer<JsonObject>) b ->
                        shop(b, "lampas:mira").add("name", JsonParser.parseString("{ \"text\": \"Mira\" }"))),
                Arguments.of("a limit per day", (Consumer<JsonObject>) b ->
                        offer(b, "lampas:mira", 1).getAsJsonObject("limit").addProperty("per", "day")),
                Arguments.of("requires with an achievement alone", (Consumer<JsonObject>) b ->
                        offer(b, "lampas:mira", 1).add("requires",
                                JsonParser.parseString("{ \"achievement\": \"lampas:guild/first_commission\" }"))),
                Arguments.of("a refuse namespace rule with no price or junk", (Consumer<JsonObject>) b -> {
                    commonDropsRule(b, 2).remove("price");
                    commonDropsRule(b, 2).remove("junk");
                    commonDropsRule(b, 2).addProperty("refuse", true);
                }),
                Arguments.of("a second denomination of value 81", (Consumer<JsonObject>) b ->
                        tradeSection(b).getAsJsonArray("currency").add(JsonParser.parseString(
                                "{ \"item\": \"minecraft:gold_block\", \"value\": 81 }"))),
                Arguments.of("an offer id of 64 characters", (Consumer<JsonObject>) b ->
                        offer(b, "lampas:mira", 0).addProperty("id", "a".repeat(64))),
                Arguments.of("an experience market with fee shops", (Consumer<JsonObject>) AlinaSchemaTest::useMarket),
                Arguments.of("an experience market with no shop that trades experience", (Consumer<JsonObject>) b -> {
                    useMarket(b);
                    shop(b, "lampas:mira").remove("experience");
                }),
                Arguments.of("an experience market without max_trade_percent", (Consumer<JsonObject>) b -> {
                    useMarket(b);
                    market(b).remove("max_trade_percent");
                }),
                Arguments.of("an experience market at the reserve bounds", (Consumer<JsonObject>) b -> {
                    useMarket(b);
                    reserve(b).addProperty("points", 1000);
                    reserve(b).addProperty("aur", 1000000000);
                }),
                Arguments.of("a fee of 0 on a sell alone", (Consumer<JsonObject>) b -> {
                    useMarket(b);
                    shop(b, "lampas:mira").getAsJsonObject("experience").remove("buy");
                    shop(b, "lampas:mira").getAsJsonObject("experience").getAsJsonObject("sell")
                            .addProperty("fee_percent", 0);
                }));
    }

    /** Changes to the trade example that the format refuses. The readers and the schema must both refuse them. */
    static Stream<Arguments> refusedTradeShapes() {
        return Stream.of(
                Arguments.of("an experience sell with no sell block", (Consumer<JsonObject>) b ->
                        shop(b, "lampas:mira").remove("sell")),
                Arguments.of("a currency value of 0", (Consumer<JsonObject>) b ->
                        tradeSection(b).getAsJsonArray("currency").get(0).getAsJsonObject().addProperty("value", 0)),
                Arguments.of("an unknown trade key", (Consumer<JsonObject>) b ->
                        tradeSection(b).addProperty("sell_all", true)),
                Arguments.of("a currency entry with an unknown key", (Consumer<JsonObject>) b ->
                        tradeSection(b).getAsJsonArray("currency").get(0).getAsJsonObject().addProperty("only_if", true)),
                Arguments.of("a rule with match and namespace", (Consumer<JsonObject>) b ->
                        commonDropsRule(b, 0).addProperty("namespace", "minecraft")),
                Arguments.of("a rule with neither match nor namespace", (Consumer<JsonObject>) b ->
                        commonDropsRule(b, 0).remove("match")),
                Arguments.of("a refuse rule with a price", (Consumer<JsonObject>) b ->
                        commonDropsRule(b, 3).add("price", JsonParser.parseString("{ \"aur\": 1, \"per\": 1 }"))),
                Arguments.of("a rule with no price and no refuse", (Consumer<JsonObject>) b ->
                        commonDropsRule(b, 3).remove("refuse")),
                Arguments.of("a refuse rule with junk", (Consumer<JsonObject>) b ->
                        commonDropsRule(b, 3).addProperty("junk", true)),
                Arguments.of("a match with a count", (Consumer<JsonObject>) b ->
                        commonDropsRule(b, 0).getAsJsonObject("match").addProperty("count", 2)),
                Arguments.of("a price per of 65", (Consumer<JsonObject>) b ->
                        commonDropsRule(b, 0).getAsJsonObject("price").addProperty("per", 65)),
                Arguments.of("a namespace with a capital letter", (Consumer<JsonObject>) b ->
                        commonDropsRule(b, 2).addProperty("namespace", "FarmersDelight")),
                Arguments.of("a shop with only a name", (Consumer<JsonObject>) b -> {
                    shop(b, "lampas:mira").remove("sell");
                    shop(b, "lampas:mira").remove("buy");
                    shop(b, "lampas:mira").remove("experience");
                }),
                Arguments.of("a shop with an empty buy list only", (Consumer<JsonObject>) b -> {
                    shop(b, "lampas:mira").remove("sell");
                    shop(b, "lampas:mira").remove("experience");
                    shop(b, "lampas:mira").add("buy", new JsonArray());
                }),
                Arguments.of("a daily budget of 0", (Consumer<JsonObject>) b ->
                        shop(b, "lampas:mira").getAsJsonObject("sell").addProperty("daily_budget", 0)),
                Arguments.of("an offer id with a capital letter", (Consumer<JsonObject>) b ->
                        offer(b, "lampas:mira", 0).addProperty("id", "XP_Flask")),
                Arguments.of("an offer id of 65 characters", (Consumer<JsonObject>) b ->
                        offer(b, "lampas:mira", 0).addProperty("id", "a".repeat(65))),
                Arguments.of("an offer item with a count of 1", (Consumer<JsonObject>) b ->
                        offer(b, "lampas:mira", 0).getAsJsonObject("item").addProperty("count", 1)),
                Arguments.of("an offer item with a count of 100", (Consumer<JsonObject>) b ->
                        offer(b, "lampas:mira", 0).getAsJsonObject("item").addProperty("count", 100)),
                Arguments.of("a limit per week", (Consumer<JsonObject>) b ->
                        offer(b, "lampas:mira", 1).getAsJsonObject("limit").addProperty("per", "week")),
                Arguments.of("requires with nothing in it", (Consumer<JsonObject>) b ->
                        offer(b, "lampas:mira", 1).add("requires", new JsonObject())),
                Arguments.of("an unknown shop line", (Consumer<JsonObject>) b ->
                        shop(b, "lampas:mira").getAsJsonObject("lines").addProperty("hello", "x")),
                Arguments.of("an experience rate of 0 points", (Consumer<JsonObject>) b ->
                        shop(b, "lampas:mira").getAsJsonObject("experience").getAsJsonObject("sell")
                                .addProperty("points", 0)),
                Arguments.of("a shop id that is not an id", (Consumer<JsonObject>) b ->
                        tradeSection(b).getAsJsonObject("shops").add("Bad Key",
                                shop(b, "lampas:mira").deepCopy())),
                Arguments.of("a sell list that is not an array", (Consumer<JsonObject>) b ->
                        shop(b, "lampas:mira").getAsJsonObject("sell").addProperty("lists", "lampas:common_drops")),
                Arguments.of("a rate form with an experience market", (Consumer<JsonObject>) b -> {
                    useMarket(b);
                    miraExperience(b, "{ \"sell\": { \"points\": 50, \"aur\": 1 } }");
                }),
                Arguments.of("a fee form without an experience market", (Consumer<JsonObject>) b ->
                        miraExperience(b, "{ \"sell\": { \"fee_percent\": 5 }, \"buy\": { \"fee_percent\": 5 } }")),
                Arguments.of("a fee with the keys of a rate", (Consumer<JsonObject>) b -> {
                    useMarket(b);
                    shop(b, "lampas:mira").getAsJsonObject("experience").getAsJsonObject("sell")
                            .addProperty("points", 50);
                }),
                Arguments.of("a fee of 51", (Consumer<JsonObject>) b -> {
                    useMarket(b);
                    shop(b, "lampas:mira").getAsJsonObject("experience").getAsJsonObject("sell")
                            .addProperty("fee_percent", 51);
                }),
                Arguments.of("a reserve of 999 points", (Consumer<JsonObject>) b -> {
                    useMarket(b);
                    reserve(b).addProperty("points", 999);
                }),
                Arguments.of("a reserve of 1000000001 aur", (Consumer<JsonObject>) b -> {
                    useMarket(b);
                    reserve(b).addProperty("aur", 1000000001);
                }),
                Arguments.of("a reserve with an unknown key", (Consumer<JsonObject>) b -> {
                    useMarket(b);
                    reserve(b).addProperty("only_if", true);
                }),
                Arguments.of("a restock of 101", (Consumer<JsonObject>) b -> {
                    useMarket(b);
                    market(b).addProperty("restock_percent", 101);
                }),
                Arguments.of("a max trade of 51", (Consumer<JsonObject>) b -> {
                    useMarket(b);
                    market(b).addProperty("max_trade_percent", 51);
                }),
                Arguments.of("an experience market without a reserve", (Consumer<JsonObject>) b -> {
                    useMarket(b);
                    market(b).remove("reserve");
                }),
                Arguments.of("an experience market without a restock", (Consumer<JsonObject>) b -> {
                    useMarket(b);
                    market(b).remove("restock_percent");
                }),
                Arguments.of("an experience market with an unknown key", (Consumer<JsonObject>) b -> {
                    useMarket(b);
                    market(b).addProperty("only_if", true);
                }),
                Arguments.of("an experience market that is not an object", (Consumer<JsonObject>) b ->
                        tradeSection(b).addProperty("experience_market", "x")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allowedTradeShapes")
    void tradeSchemaAndReadersBothAllow(String name, Consumer<JsonObject> change) {
        JsonObject bundle = bundle();
        change.accept(bundle);
        assertAll(
                () -> assertEquals(List.of(), readerProblems(bundle), "the readers refuse it"),
                () -> assertEquals(List.of(), errors(bundle), "the schema refuses it"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedTradeShapes")
    void tradeSchemaAndReadersBothRefuse(String name, Consumer<JsonObject> change) {
        JsonObject bundle = bundle();
        change.accept(bundle);
        assertAll(
                () -> assertFalse(readerProblems(bundle).isEmpty(), "the readers accept it"),
                () -> assertFalse(errors(bundle).isEmpty(), "the schema accepts it"));
    }
}
