package town.lampas.alina.settlement;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import town.lampas.alina.storage.CommissionStatus;
import town.lampas.alina.storage.OperationEffect;
import town.lampas.alina.storage.OperationKind;

class SettlementRequestTest {
    private static final Identifier COMMISSION = Identifier.fromNamespaceAndPath("alina", "guild/courier");

    private static SettlementRequest turnIn(Optional<Character> category, Optional<String> kind) {
        return new SettlementRequest(OperationKind.TURN_IN, COMMISSION, 0, 1, Optional.empty(), 1,
                new OperationEffect.CommissionSettled(COMMISSION, CommissionStatus.COMPLETED, Optional.empty()),
                player -> List.of(), category, kind);
    }

    @Test
    void aTurnInNamesItsCategoryAndKindTogetherOrNotAtAll() {
        turnIn(Optional.of('C'), Optional.of("repeatable"));
        turnIn(Optional.empty(), Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> turnIn(Optional.of('C'), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> turnIn(Optional.empty(), Optional.of("feat")));
    }

    @Test
    void theCategoryIsAToFAndTheKindIsRepeatableOrFeat() {
        turnIn(Optional.of('F'), Optional.of("feat"));

        assertThrows(IllegalArgumentException.class, () -> turnIn(Optional.of('G'), Optional.of("feat")));
        assertThrows(IllegalArgumentException.class, () -> turnIn(Optional.of('C'), Optional.of("daily")));
    }

    @Test
    void onlyATurnInNamesACommission() {
        assertThrows(IllegalArgumentException.class, () -> new SettlementRequest(OperationKind.ACHIEVEMENT_REWARD,
                COMMISSION, 1, 1, Optional.empty(), 0,
                new OperationEffect.AchievementEarned(COMMISSION, 1, Optional.empty()), player -> List.of(),
                Optional.of('C'), Optional.of("repeatable")));
    }
}
