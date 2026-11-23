package town.lampas.alina.storage;

import java.util.Objects;
import net.minecraft.resources.Identifier;

/**
 * One offer of one shop, counted under one period: an ISO date for a per-day limit, or {@code character} for a per-character
 * limit. See {@link OperationEffect.TradeSettled.Purchase}.
 */
public record TradePurchaseKey(Identifier shopId, String offerId, String period) {

    public TradePurchaseKey {
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(offerId, "offerId");
        Objects.requireNonNull(period, "period");
    }
}
