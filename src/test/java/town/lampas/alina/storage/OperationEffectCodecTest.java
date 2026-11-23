package town.lampas.alina.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.LocalDate;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class OperationEffectCodecTest {
    private static final Identifier STALL = Identifier.parse("alina:shop/stall");
    private static final LocalDate DAY = LocalDate.parse("2026-11-23");

    @Test
    void aTradeEffectReadsBackAsItWasWritten() {
        OperationEffect.TradeSettled trade = new OperationEffect.TradeSettled(STALL, DAY, 30,
                List.of(new OperationEffect.TradeSettled.Purchase("scroll", "2026-11-23", 2),
                        new OperationEffect.TradeSettled.Purchase("charm", "character", 1)),
                "{\"bought\":\"scroll\",\"sold\":[1,2]}");

        assertEquals(trade, OperationEffectCodec.fromJson(OperationEffectCodec.toJson(trade)));
    }

    @Test
    void aTradeWithNoPurchasesAndNoAurReadsBackAsItWasWritten() {
        OperationEffect.TradeSettled trade = new OperationEffect.TradeSettled(STALL, DAY, 0, List.of(), "{}");

        assertEquals(trade, OperationEffectCodec.fromJson(OperationEffectCodec.toJson(trade)));
    }

    @Test
    void aTradeIsStoredUnderTheKeysTheStorageSpecNames() {
        OperationEffect.TradeSettled trade = new OperationEffect.TradeSettled(STALL, DAY, 30,
                List.of(new OperationEffect.TradeSettled.Purchase("scroll", "2026-11-23", 2)),
                "{\"sold\":\"paper\"}");

        JsonObject json = JsonParser.parseString(OperationEffectCodec.toJson(trade)).getAsJsonObject();

        assertEquals("trade_settled", json.get("type").getAsString());
        assertEquals("alina:shop/stall", json.get("shop_id").getAsString());
        assertEquals("2026-11-23", json.get("trade_day").getAsString());
        assertEquals(30, json.get("aur_paid").getAsInt());
        JsonObject bought = json.getAsJsonArray("purchases").get(0).getAsJsonObject();
        assertEquals("scroll", bought.get("offer_id").getAsString());
        assertEquals("2026-11-23", bought.get("period").getAsString());
        assertEquals(2, bought.get("count").getAsInt());
        assertEquals("paper", json.getAsJsonObject("detail").get("sold").getAsString());
    }

    @Test
    void aTradeWithMarketDeltasReadsBackAsItWasWritten() {
        OperationEffect.TradeSettled trade = new OperationEffect.TradeSettled(STALL, DAY, 30,
                List.of(new OperationEffect.TradeSettled.Purchase("scroll", "2026-11-23", 2)),
                "{\"bought\":\"scroll\",\"sold\":[1,2]}", 5051, -100);

        assertEquals(trade, OperationEffectCodec.fromJson(OperationEffectCodec.toJson(trade)));
    }

    /** A trade written before the market existed has no market keys, and reads as no change to either reserve. */
    @Test
    void aTradeWithoutMarketKeysReadsAsNoMarketChange() {
        String written = "{\"type\":\"trade_settled\",\"shop_id\":\"alina:shop/stall\",\"trade_day\":\"2026-11-23\","
                + "\"aur_paid\":30,\"purchases\":[],\"detail\":{}}";

        OperationEffect.TradeSettled read =
                assertInstanceOf(OperationEffect.TradeSettled.class, OperationEffectCodec.fromJson(written));

        assertEquals(0, read.marketPoints());
        assertEquals(0, read.marketAur());
    }

    @Test
    void aTradeWithZeroMarketDeltasWritesNeitherMarketKey() {
        OperationEffect.TradeSettled trade = new OperationEffect.TradeSettled(STALL, DAY, 30, List.of(), "{}", 0, 0);

        JsonObject json = JsonParser.parseString(OperationEffectCodec.toJson(trade)).getAsJsonObject();

        assertFalse(json.has("market_points"));
        assertFalse(json.has("market_aur"));
    }

    @Test
    void aTradeDetailReadsBackInCanonicalFormAsTheRewardDoes() {
        OperationEffect.TradeSettled written = new OperationEffect.TradeSettled(STALL, DAY, 0, List.of(),
                "{ \"sold\": \"paper\", \"bought\": 2 }");

        OperationEffect read = OperationEffectCodec.fromJson(OperationEffectCodec.toJson(written));

        assertEquals("{\"bought\":2,\"sold\":\"paper\"}",
                assertInstanceOf(OperationEffect.TradeSettled.class, read).detailJson());
    }
}
