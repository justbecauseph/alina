package town.lampas.alina.trade.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import town.lampas.alina.content.GuildSettings;
import town.lampas.alina.content.TradeContent;
import town.lampas.alina.content.TradeContent.ExperienceMarket;
import town.lampas.alina.content.TradeContent.Selling;
import town.lampas.alina.content.TradeContent.Shop;
import town.lampas.alina.storage.InMemoryStorage;
import town.lampas.alina.storage.Operation;
import town.lampas.alina.storage.OperationEffect;
import town.lampas.alina.storage.OperationKind;
import town.lampas.alina.storage.OperationStep;
import town.lampas.alina.trade.TradeService;
import town.lampas.alina.trade.pricing.ReserveCurve.Reserves;

class TradeMethodsTest {
    private static final Instant NOW = Instant.parse("2026-11-24T02:00:00Z"); // 10:00 Asia/Manila -> 2026-11-24
    private static final LocalDate BOARD_DAY = LocalDate.parse("2026-11-24");
    private static final GuildSettings SETTINGS = GuildSettings.DEFAULTS;
    private static final Identifier STALL = Identifier.parse("alina:shop/stall");
    private static final Identifier BAZAAR = Identifier.parse("alina:shop/bazaar");
    private static final UUID ALICE = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final UUID BOB = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    private final InMemoryStorage storage = new InMemoryStorage();

    @AfterEach
    void closeStorage() {
        storage.close();
    }

    @Test
    void marketReturnsConfiguredLoadedStateAndAscendingTicksAfterId() throws Exception {
        ExperienceMarket terms = new ExperienceMarket(500_000, 10_000, 10, 15, 2);
        TradeContent content = new TradeContent(List.of(), Map.of(), Map.of(), Optional.of(terms));
        TradeService.MarketStatus status = new TradeService.MarketStatus(
                new Reserves(510_000, 9_800), new Reserves(500_000, 10_000),
                BOARD_DAY.minusDays(1), 2, 12, 3, 1);

        storage.loadOrCreateMarket(500_000, 10_000, BOARD_DAY.minusDays(1), NOW).get(5, TimeUnit.SECONDS);
        storage.adjustMarket(5_000, -100, NOW.plusSeconds(10)).get(5, TimeUnit.SECONDS);
        storage.adjustMarket(5_000, -100, NOW.plusSeconds(20)).get(5, TimeUnit.SECONDS);

        TradeMethods methods = new TradeMethods(
                Runnable::run, () -> Optional.of(status), storage, () -> content, () -> SETTINGS,
                Clock.fixed(NOW, ZoneId.of("UTC")));

        JsonObject firstPage = methods.market(JsonParser.parseString("{\"after_id\":0,\"limit\":1}"));
        assertEquals("ok", firstPage.get("status").getAsString());
        assertEquals("2026-11-24", firstPage.get("board_day").getAsString());
        assertTrue(firstPage.get("configured").getAsBoolean());
        assertTrue(firstPage.get("loaded").getAsBoolean());

        JsonObject market = firstPage.getAsJsonObject("market");
        assertEquals(510_000L, market.get("live_points").getAsLong());
        assertEquals(9_800L, market.get("live_aur").getAsLong());
        assertFalse(market.has("points"));
        assertFalse(market.has("aur"));
        assertEquals(5204L, market.get("rate_hundredths").getAsLong());
        assertEquals(500_000L, market.get("seed_points").getAsLong());
        assertEquals(10_000L, market.get("seed_aur").getAsLong());
        assertEquals(5000L, market.get("base_rate_hundredths").getAsLong());
        assertEquals("2026-11-23", market.get("restocked_day").getAsString());
        assertEquals(10, market.get("restock_percent").getAsInt());
        assertEquals(15, market.get("max_trade_percent").getAsInt());
        assertEquals(2, market.get("quote_tolerance_percent").getAsInt());
        assertEquals(12L, market.get("requests").getAsLong());
        assertEquals(3L, market.get("moved_accepted").getAsLong());
        assertEquals(1L, market.get("stale_refused").getAsLong());

        JsonArray ticks1 = firstPage.getAsJsonArray("ticks");
        assertEquals(1, ticks1.size());
        long firstId = ticks1.get(0).getAsJsonObject().get("id").getAsLong();
        assertEquals(505_000L, ticks1.get(0).getAsJsonObject().get("points").getAsLong());
        assertEquals(5101L, ticks1.get(0).getAsJsonObject().get("rate_hundredths").getAsLong());

        JsonObject secondPage = methods.market(JsonParser.parseString("{\"after_id\":" + firstId + "}"));
        JsonArray ticks2 = secondPage.getAsJsonArray("ticks");
        assertEquals(1, ticks2.size());
        assertEquals(510_000L, ticks2.get(0).getAsJsonObject().get("points").getAsLong());
    }

    @Test
    void marketHandlesUnconfiguredAndUnloadedStates() {
        TradeMethods unconfigured = new TradeMethods(
                Runnable::run, Optional::empty, storage, () -> TradeContent.EMPTY, () -> SETTINGS,
                Clock.fixed(NOW, ZoneId.of("UTC")));

        JsonObject answer = unconfigured.market(null);
        assertEquals("ok", answer.get("status").getAsString());
        assertFalse(answer.get("configured").getAsBoolean());
        assertFalse(answer.get("loaded").getAsBoolean());
        assertFalse(answer.has("market"));
        assertEquals(0, answer.getAsJsonArray("ticks").size());
    }

    @Test
    void summaryUsesDefault14DayWindowAndValidatesRange() throws Exception {
        Shop stall = new Shop(STALL, Component.literal("Stall"), Optional.of(new Selling(100, List.of())),
                List.of(), Optional.empty(), Optional.empty(), Map.of());
        TradeContent content = new TradeContent(List.of(), Map.of(), Map.of(STALL, stall), Optional.empty());
        UUID op1 = UUID.fromString("11111111-1111-4111-8111-111111111111");
        storage.applyOperation(new Operation(op1, ALICE, OperationKind.TRADE, STALL, 0, 4, "minecraft:overworld",
                "{}", 0, new OperationEffect.TradeSettled(STALL, BOARD_DAY.minusDays(13), 40, List.of(),
                "{\"sold\":[{\"item\":\"minecraft:bone\",\"count\":4,\"aur\":40}],\"bought\":[],\"experience_sold\":0,\"experience_bought\":0,\"aur_spent\":0}"),
                NOW)).get(5, TimeUnit.SECONDS);

        TradeMethods methods = new TradeMethods(
                Runnable::run, Optional::empty, storage, () -> content, () -> SETTINGS,
                Clock.fixed(NOW, ZoneId.of("UTC")));

        JsonObject ok = methods.summary(new JsonObject());
        assertEquals("ok", ok.get("status").getAsString());
        assertEquals("2026-11-24", ok.get("board_day").getAsString());
        assertEquals("2026-11-11", ok.get("from_day").getAsString());
        assertEquals("2026-11-24", ok.get("to_day").getAsString());
        assertEquals(1, ok.getAsJsonArray("daily").size());
        assertEquals(40L, ok.getAsJsonObject("concentration").get("total_aur_minted").getAsLong());
        assertEquals(1, ok.getAsJsonObject("health").get("recorded_count").getAsInt());

        // 90 inclusive days (between == 89) is allowed; 91 inclusive days (between == 90) is refused.
        JsonObject ninetyDays = methods.summary(JsonParser.parseString(
                "{\"from_day\":\"2026-08-27\",\"to_day\":\"2026-11-24\"}"));
        assertEquals("ok", ninetyDays.get("status").getAsString());

        JsonObject ninetyOneDays = methods.summary(JsonParser.parseString(
                "{\"from_day\":\"2026-08-26\",\"to_day\":\"2026-11-24\"}"));
        assertEquals("invalid", ninetyOneDays.get("status").getAsString());
        assertEquals("from_day", ninetyOneDays.get("field").getAsString());

        JsonObject inverted = methods.summary(JsonParser.parseString(
                "{\"from_day\":\"2026-11-25\",\"to_day\":\"2026-11-24\"}"));
        assertEquals("invalid", inverted.get("status").getAsString());
        assertEquals("from_day", inverted.get("field").getAsString());
    }

    @Test
    void operationsPaginatesWithCursorAndFiltersByShopPlayerAndSteps() throws Exception {
        UUID op1 = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID op2 = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID op3 = UUID.fromString("33333333-3333-4333-8333-333333333333");
        storage.applyOperation(new Operation(op1, ALICE, OperationKind.TRADE, STALL, 0, 1, "world", "{}", 0,
                new OperationEffect.TradeSettled(STALL, BOARD_DAY, 10, List.of(), "{}"), NOW)).get(5, TimeUnit.SECONDS);
        storage.applyOperation(new Operation(op2, BOB, OperationKind.TRADE, BAZAAR, 0, 1, "world", "{}", 0,
                new OperationEffect.TradeSettled(BAZAAR, BOARD_DAY, 0,
                        List.of(new OperationEffect.TradeSettled.Purchase("scroll", "character", 1)),
                        "{\"sold\":[],\"bought\":[{\"offer\":\"scroll\",\"item\":\"minecraft:paper\",\"count\":1,\"aur\":5}],\"experience_sold\":0,\"experience_bought\":12,\"aur_spent\":5}"),
                NOW.plusSeconds(1))).get(5, TimeUnit.SECONDS);
        storage.applyOperation(new Operation(op3, ALICE, OperationKind.TRADE, STALL, 0, 1, "world", "{}", 0,
                new OperationEffect.TradeSettled(STALL, BOARD_DAY, 20, List.of(), "{}"), NOW.plusSeconds(2)))
                .get(5, TimeUnit.SECONDS);
        storage.markOperation(op2, OperationStep.SETTLED, Optional.empty(), NOW.plusSeconds(3)).get(5, TimeUnit.SECONDS);

        TradeMethods methods = new TradeMethods(
                Runnable::run, Optional::empty, storage, () -> TradeContent.EMPTY, () -> SETTINGS,
                Clock.fixed(NOW, ZoneId.of("UTC")));

        JsonObject page1 = methods.operations(JsonParser.parseString("{\"limit\":2}"));
        assertEquals("ok", page1.get("status").getAsString());
        assertEquals(2, page1.getAsJsonArray("operations").size());
        assertEquals(op3.toString(),
                page1.getAsJsonArray("operations").get(0).getAsJsonObject().get("operation_id").getAsString());
        assertEquals(op2.toString(),
                page1.getAsJsonArray("operations").get(1).getAsJsonObject().get("operation_id").getAsString());
        assertTrue(page1.has("next"));

        JsonObject page2 = methods.operations(JsonParser.parseString(
                "{\"limit\":2,\"before\":\"" + page1.get("next").getAsString() + "\"}"));
        assertEquals(1, page2.getAsJsonArray("operations").size());
        assertEquals(op1.toString(),
                page2.getAsJsonArray("operations").get(0).getAsJsonObject().get("operation_id").getAsString());
        assertFalse(page2.has("next"));

        JsonObject filtered = methods.operations(JsonParser.parseString(
                "{\"shop\":\"alina:shop/bazaar\",\"player\":\"" + BOB + "\",\"steps\":[\"settled\"]}"));
        assertEquals(1, filtered.getAsJsonArray("operations").size());
        JsonObject op2Json = filtered.getAsJsonArray("operations").get(0).getAsJsonObject();
        assertEquals(op2.toString(), op2Json.get("operation_id").getAsString());
        assertFalse(op2Json.has("detail"));
        assertEquals(5L, op2Json.get("aur_spent").getAsLong());
        assertEquals(0L, op2Json.get("xp_sold").getAsLong());
        assertEquals(12L, op2Json.get("xp_bought").getAsLong());
        assertEquals(0, op2Json.getAsJsonArray("sold").size());
        assertEquals(1, op2Json.getAsJsonArray("bought").size());
        assertEquals("scroll", op2Json.getAsJsonArray("bought").get(0).getAsJsonObject().get("offer").getAsString());

        JsonObject op3Json = page1.getAsJsonArray("operations").get(0).getAsJsonObject();
        assertEquals(0L, op3Json.get("aur_spent").getAsLong());
        assertEquals(0L, op3Json.get("xp_sold").getAsLong());
        assertEquals(0L, op3Json.get("xp_bought").getAsLong());
        assertEquals(0, op3Json.getAsJsonArray("sold").size());
        assertEquals(0, op3Json.getAsJsonArray("bought").size());
    }

    @Test
    void invalidParamsAndUnavailableServerReportCleanErrorsWithoutQuotingValues() {
        TradeMethods methods = new TradeMethods(
                task -> { throw new TradeMethods.Unavailable("Server stopped"); },
                Optional::empty, storage, () -> TradeContent.EMPTY, () -> SETTINGS,
                Clock.fixed(NOW, ZoneId.of("UTC")));

        assertInvalid(methods.market(new JsonPrimitive("bad-token-secret")), "", "bad-token-secret");
        assertInvalid(methods.market(JsonParser.parseString("{\"after_id\":-1}")), "after_id", null);
        assertInvalid(methods.market(JsonParser.parseString("{\"limit\":0}")), "limit", null);
        assertInvalid(methods.summary(JsonParser.parseString("{\"from_day\":\"2026-1-5\"}")), "from_day", "2026-1-5");
        assertInvalid(methods.operations(JsonParser.parseString("{\"player\":\"not-a-uuid\"}")), "player", "not-a-uuid");
        assertInvalid(methods.operations(JsonParser.parseString("{\"steps\":[\"bogus\"]}")), "steps", "bogus");
        assertInvalid(methods.operations(JsonParser.parseString("{\"steps\":[]}")), "steps", null);
        assertInvalid(methods.operations(JsonParser.parseString("{\"before\":\"bad|cursor\"}")), "before", "bad");

        JsonObject unavailable = methods.market(new JsonObject());
        assertEquals("unavailable", unavailable.get("status").getAsString());
        assertEquals("Server stopped", unavailable.get("message").getAsString());
    }

    private static void assertInvalid(JsonObject answer, String field, String secretNotQuoted) {
        assertEquals("invalid", answer.get("status").getAsString(), "answer " + answer);
        assertEquals(field, answer.get("field").getAsString(), "answer " + answer);
        if (secretNotQuoted != null) {
            assertFalse(answer.get("message").getAsString().contains(secretNotQuoted), answer.toString());
        }
    }
}
