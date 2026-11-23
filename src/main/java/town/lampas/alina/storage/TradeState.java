package town.lampas.alina.storage;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The trade rows of one character that still count: the Aur paid per shop and day from the requested day on, and the
 * purchase counts per offer and period that still count from it. The maps are immutable copies. A character with no trade
 * rows has empty maps.
 */
public record TradeState(UUID characterId, Map<TradeBudgetKey, Integer> aurPaid, Map<TradePurchaseKey, Integer> purchases) {

    public TradeState {
        Objects.requireNonNull(characterId, "characterId");
        aurPaid = Map.copyOf(aurPaid);
        purchases = Map.copyOf(purchases);
    }
}
