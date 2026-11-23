package town.lampas.alina.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** The value rules the records enforce before any backend sees them. */
class StorageRecordsTest {
    private static final Identifier COURIER = Identifier.parse("alina:guild/courier");
    private static final Identifier FIRST_STEPS = Identifier.parse("alina:achievement/first_steps");
    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");
    private static final UUID ALICE = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final Identifier BOARD = Identifier.parse("alina:board");
    private static final Identifier STALL = Identifier.parse("alina:shop/stall");
    private static final LocalDate DAY = LocalDate.parse("2026-11-05");

    @Test
    void aTurnInCannotSettleACommissionToActive() {
        assertThrows(IllegalArgumentException.class,
                () -> new OperationEffect.CommissionSettled(COURIER, CommissionStatus.ACTIVE, Optional.empty()));
    }

    @Test
    void aTurnInToCooldownNeedsTheEndOfTheCooldown() {
        assertThrows(IllegalArgumentException.class,
                () -> new OperationEffect.CommissionSettled(COURIER, CommissionStatus.COOLDOWN, Optional.empty()));
    }

    @Test
    void aTurnInToCompletedHasNoCooldownToEnd() {
        assertThrows(IllegalArgumentException.class,
                () -> new OperationEffect.CommissionSettled(COURIER, CommissionStatus.COMPLETED, Optional.of(T0)));
    }

    @Test
    void aTurnInWhoseCooldownMatchesItsStatusIsAccepted() {
        assertDoesNotThrow(
                () -> new OperationEffect.CommissionSettled(COURIER, CommissionStatus.COOLDOWN, Optional.of(T0)));
        assertDoesNotThrow(
                () -> new OperationEffect.CommissionSettled(COURIER, CommissionStatus.COMPLETED, Optional.empty()));
    }

    @Test
    void aNegativeTierOrProgressIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new OperationEffect.AchievementEarned(FIRST_STEPS, -1, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new AchievementState(FIRST_STEPS, -1, 0, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new CommissionState(
                COURIER, CommissionStatus.ACTIVE, -1, T0, Optional.empty(), Optional.empty(), 1));
    }

    @Test
    void aCharacterStateHoldsImmutableCopies() {
        Map<Identifier, CommissionState> commissions = new HashMap<>();
        commissions.put(COURIER, new CommissionState(
                COURIER, CommissionStatus.ACTIVE, 0, T0, Optional.empty(), Optional.empty(), 1));

        CharacterState state = new CharacterState(ALICE, 2, commissions, Map.of(), Map.of());
        commissions.clear();

        assertEquals(1, state.commissions().size());
        assertThrows(UnsupportedOperationException.class, () -> state.commissions().clear());
    }

    @Test
    void aBoardKeepsItsOrderAndIsImmutable() {
        List<Identifier> ids = new ArrayList<>(List.of(COURIER, FIRST_STEPS));
        StoredBoard board = new StoredBoard(LocalDate.parse("2026-11-05"), BOARD, 1, 2, ids);
        ids.clear();

        assertEquals(List.of(COURIER, FIRST_STEPS), board.commissionIds());
        assertThrows(UnsupportedOperationException.class, () -> board.commissionIds().add(COURIER));
    }

    @Test
    void aBoardNeedsItsBoardId() {
        assertThrows(NullPointerException.class,
                () -> new StoredBoard(LocalDate.parse("2026-11-05"), null, 1, 2, List.of(COURIER)));
    }

    @Test
    void aPurchasesPeriodIsAnIsoDateOrCharacter() {
        new OperationEffect.TradeSettled.Purchase("charm", "character", 1);
        new OperationEffect.TradeSettled.Purchase("charm", "2026-11-23", 1);

        assertThrows(IllegalArgumentException.class,
                () -> new OperationEffect.TradeSettled.Purchase("charm", "monthly", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new OperationEffect.TradeSettled.Purchase("charm", "2026-1-5", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new OperationEffect.TradeSettled.Purchase("charm", "2026-11-31", 1));
    }

    @Test
    void aPurchaseCountsAtLeastOneOfAnOffer() {
        assertThrows(IllegalArgumentException.class,
                () -> new OperationEffect.TradeSettled.Purchase("charm", "character", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new OperationEffect.TradeSettled.Purchase(" ", "character", 1));
    }

    @Test
    void aTradePaysNoNegativeAurAndKeepsItsDetailAsAJsonObject() {
        assertThrows(IllegalArgumentException.class,
                () -> new OperationEffect.TradeSettled(STALL, DAY, -1, List.of(), "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> new OperationEffect.TradeSettled(STALL, DAY, 1, List.of(), "[]"));
        assertThrows(IllegalArgumentException.class,
                () -> new OperationEffect.TradeSettled(STALL, DAY, 1, List.of(), "not json"));
        assertThrows(NullPointerException.class,
                () -> new OperationEffect.TradeSettled(STALL, DAY, 1, List.of(), null));
        assertDoesNotThrow(() -> new OperationEffect.TradeSettled(STALL, DAY, 0, List.of(), "{}"));
    }

    @Test
    void aMarketStateHoldsAnyReserveBecauseItIsTheExactSum() {
        // Trades and restocks add exactly, so a reserve may pass below 1 or above the cap; the server clamps what it reads.
        assertDoesNotThrow(() -> new MarketState(0, 10_000, DAY));
        assertDoesNotThrow(() -> new MarketState(500_000, -5_000, DAY));
        assertDoesNotThrow(() -> new MarketState(5_000_000_000L, 10_000, DAY));
        assertThrows(NullPointerException.class, () -> new MarketState(500_000, 10_000, null));
    }

    @Test
    void aMarketTickHoldsAnyReserveAndNeedsAnInstant() {
        Instant at = Instant.parse("2026-11-05T10:00:00Z");
        assertDoesNotThrow(() -> new MarketTick(0, -5_000, at));
        assertDoesNotThrow(() -> new MarketTick(5_000_000_000L, 1, at));
        assertThrows(NullPointerException.class, () -> new MarketTick(1, 1, null));
    }

    @Test
    void aTradeCopiesItsPurchases() {
        List<OperationEffect.TradeSettled.Purchase> purchases = new ArrayList<>(List.of(
                new OperationEffect.TradeSettled.Purchase("charm", "character", 1)));
        OperationEffect.TradeSettled trade = new OperationEffect.TradeSettled(STALL, DAY, 1, purchases, "{}");
        purchases.clear();

        assertEquals(1, trade.purchases().size());
        assertThrows(UnsupportedOperationException.class, () -> trade.purchases().clear());
    }
}
