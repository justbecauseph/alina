package town.lampas.alina.trade;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import town.lampas.alina.storage.TradeBudgetKey;
import town.lampas.alina.storage.TradePurchaseKey;

/**
 * One online character's trade record: the Aur paid per shop and day, the purchases per offer and period, and the trades
 * settlement has not reported settled yet. Server thread only. The rows are read once per join; a trade the service begins
 * is added here when settlement accepts it.
 */
final class TradeLedger {
    final UUID id;
    /** True once the storage rows have been read. Every request is refused with a loading line until then. */
    boolean loaded;
    boolean loadInFlight;
    /** Which load is current, so a completion of an earlier load is ignored. */
    long loadGeneration;
    /** The tick a failed load may be tried again at. */
    long retryAt;
    final Map<TradeBudgetKey, Integer> aurPaid = new HashMap<>();
    final Map<TradePurchaseKey, Integer> purchases = new HashMap<>();
    /** Operation ids of the trades begun and not yet reported settled. */
    final Set<UUID> inFlight = new HashSet<>();

    TradeLedger(UUID id) {
        this.id = id;
    }

    boolean busy() {
        return !inFlight.isEmpty();
    }

    int aurPaid(Identifier shop, LocalDate day) {
        return aurPaid.getOrDefault(new TradeBudgetKey(shop, day), 0);
    }

    int purchased(Identifier shop, String offerId, String period) {
        return purchases.getOrDefault(new TradePurchaseKey(shop, offerId, period), 0);
    }

    void addAurPaid(Identifier shop, LocalDate day, int aur) {
        aurPaid.merge(new TradeBudgetKey(shop, day), aur, Math::addExact);
    }

    void addPurchase(Identifier shop, String offerId, String period, int count) {
        purchases.merge(new TradePurchaseKey(shop, offerId, period), count, Integer::sum);
    }

    /** Forgets the Aur paid on the day, for one shop or for every shop. */
    void clearBudget(Optional<Identifier> shop, LocalDate day) {
        aurPaid.keySet().removeIf(key -> key.tradeDay().equals(day)
                && shop.map(id -> id.equals(key.shopId())).orElse(true));
    }
}
