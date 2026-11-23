package town.lampas.alina.storage;

import java.time.LocalDate;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/** One shop's trade budget for one day: the Aur a character has paid it on that day. */
public record TradeBudgetKey(Identifier shopId, LocalDate tradeDay) {

    public TradeBudgetKey {
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(tradeDay, "tradeDay");
    }
}
