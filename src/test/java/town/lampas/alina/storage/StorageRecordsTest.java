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
        StoredBoard board = new StoredBoard(LocalDate.parse("2026-11-05"), 1, 2, ids);
        ids.clear();

        assertEquals(List.of(COURIER, FIRST_STEPS), board.commissionIds());
        assertThrows(UnsupportedOperationException.class, () -> board.commissionIds().add(COURIER));
    }
}
