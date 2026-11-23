package town.lampas.alina.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import town.lampas.alina.storage.ApplyResult;
import town.lampas.alina.storage.OperationEffect;
import town.lampas.alina.storage.OperationKind;

class EscrowJsonTest {
    /** No registry is needed: these operations hold no stacks. */
    private static final HolderLookup.Provider NO_REGISTRIES = HolderLookup.Provider.create(Stream.empty());
    private static final UUID OPERATION = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID WORLD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant CREATED = Instant.parse("2026-11-23T10:00:00Z");
    private static final Identifier STALL = Identifier.parse("alina:shop/stall");
    private static final Identifier FIRST_STEPS = Identifier.parse("alina:achievement/first_steps");

    @Test
    void aTradeWithExperienceReadsBackAsWritten() {
        OperationEffect.TradeSettled trade = new OperationEffect.TradeSettled(STALL, LocalDate.parse("2026-11-23"), 30,
                List.of(new OperationEffect.TradeSettled.Purchase("scroll", "2026-11-23", 2),
                        new OperationEffect.TradeSettled.Purchase("charm", "character", 1)),
                "{\"bought\":\"scroll\",\"sold\":\"paper\"}");
        PendingOperation pending = pending(OperationKind.TRADE, trade, 7);

        String json = EscrowJson.encode(List.of(pending), NO_REGISTRIES);

        assertTrue(json.contains("\"experience\":7"), json);
        assertEquals(List.of(pending), EscrowJson.decode(json, NO_REGISTRIES));
    }

    @Test
    void aTradeWithMarketDeltasReadsBackAsWritten() {
        OperationEffect.TradeSettled trade = new OperationEffect.TradeSettled(STALL, LocalDate.parse("2026-11-23"), 30,
                List.of(new OperationEffect.TradeSettled.Purchase("scroll", "2026-11-23", 2)),
                "{\"bought\":\"scroll\",\"sold\":\"paper\"}", 5051, -100);
        PendingOperation pending = pending(OperationKind.TRADE, trade, 0);

        String json = EscrowJson.encode(List.of(pending), NO_REGISTRIES);

        assertEquals(List.of(pending), EscrowJson.decode(json, NO_REGISTRIES));
    }

    /** An escrow trade written before the market existed has no market keys, and reads as no change to either reserve. */
    @Test
    void anEscrowTradeWithoutMarketKeysReadsAsNoMarketChange() {
        String written = """
                [{"operation_id":"11111111-1111-4111-8111-111111111111","kind":"trade",
                "subject":"alina:shop/stall","tier":1,"revision_id":7,
                "world_id":"22222222-2222-4222-8222-222222222222","reputation":0,
                "effect":{"type":"trade_settled","shop_id":"alina:shop/stall","trade_day":"2026-11-23",
                "aur_paid":30,"purchases":[],"detail":{}},
                "created_at":"2026-11-23T10:00:00Z","rolled":[],"remaining":[],"removed":[]}]
                """;

        OperationEffect.TradeSettled trade = assertInstanceOf(OperationEffect.TradeSettled.class,
                EscrowJson.decode(written, NO_REGISTRIES).get(0).effect());

        assertEquals(0, trade.marketPoints());
        assertEquals(0, trade.marketAur());
    }

    @Test
    void aTradeWithZeroMarketDeltasWritesNeitherMarketKey() {
        PendingOperation pending = pending(OperationKind.TRADE,
                new OperationEffect.TradeSettled(STALL, LocalDate.parse("2026-11-23"), 0, List.of(), "{}", 0, 0), 0);

        String json = EscrowJson.encode(List.of(pending), NO_REGISTRIES);

        assertFalse(json.contains("\"market_points\""), json);
        assertFalse(json.contains("\"market_aur\""), json);
    }

    @Test
    void experienceAndAnAppliedResultReadBackAsWritten() {
        PendingOperation pending = pending(OperationKind.ACHIEVEMENT_REWARD,
                new OperationEffect.AchievementEarned(FIRST_STEPS, 1, Optional.empty()), 12)
                .withApplied(new ApplyResult(true, 4));

        String json = EscrowJson.encode(List.of(pending), NO_REGISTRIES);

        assertEquals(List.of(pending), EscrowJson.decode(json, NO_REGISTRIES));
    }

    @Test
    void noExperienceWritesNoKeyAndReadsBackAsNone() {
        PendingOperation pending = pending(OperationKind.ACHIEVEMENT_REWARD,
                new OperationEffect.AchievementEarned(FIRST_STEPS, 1, Optional.empty()), 0);

        String json = EscrowJson.encode(List.of(pending), NO_REGISTRIES);

        assertFalse(json.contains("\"experience\""), json);
        assertEquals(0, EscrowJson.decode(json, NO_REGISTRIES).get(0).experience());
    }

    /** An escrow written before experience existed: no experience key, so it reads as none due. */
    @Test
    void anEscrowWithoutTheExperienceKeyReadsAsNoExperienceDue() {
        String written = """
                [{"operation_id":"11111111-1111-4111-8111-111111111111","kind":"achievement_reward",
                "subject":"alina:achievement/first_steps","tier":1,"revision_id":7,
                "world_id":"22222222-2222-4222-8222-222222222222","reputation":0,
                "effect":{"type":"achievement_earned","achievement_id":"alina:achievement/first_steps","tier":1},
                "created_at":"2026-11-23T10:00:00Z","rolled":[],"remaining":[],"removed":[]}]
                """;

        List<PendingOperation> read = EscrowJson.decode(written, NO_REGISTRIES);

        assertEquals(1, read.size());
        assertEquals(0, read.get(0).experience());
        assertEquals(OPERATION, read.get(0).operationId());
    }

    @Test
    void aNegativeExperienceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> pending(OperationKind.TRADE,
                new OperationEffect.TradeSettled(STALL, LocalDate.parse("2026-11-23"), 0, List.of(), "{}"), -1));
    }

    private static PendingOperation pending(OperationKind kind, OperationEffect effect, int experience) {
        return new PendingOperation(OPERATION, kind, kind == OperationKind.TRADE ? STALL : FIRST_STEPS, 1, 7, WORLD,
                2, effect, CREATED, List.of(), List.of(), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), experience);
    }
}
