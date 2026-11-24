package town.lampas.alina.storage;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * Read-only trade economy summary over a window of trade days, plus global trade operation health counts.
 *
 * @param daily per-day totals, ordered by {@code tradeDay} ascending, for days with trades
 * @param shops per-shop totals and budget pressure, sorted by {@code aurMinted + aurSunk} descending, then {@code shopId}
 * @param topSoldItems top items sold to shops, sorted by {@code aur} descending, {@code count} descending, {@code item} ascending
 * @param topBoughtOffers top offers bought from shops, sorted by {@code aur} descending, {@code count} descending, {@code item} ascending
 * @param concentration Aur minting concentration across earners in the window
 * @param health counts of all trade operations at {@code RECORDED} and {@code RECONCILE} across all dates
 */
public record TradeSummary(
        List<DailyTotals> daily,
        List<ShopTotals> shops,
        List<ItemFlow> topSoldItems,
        List<OfferFlow> topBoughtOffers,
        Concentration concentration,
        OperationHealth health) {

    public TradeSummary {
        daily = List.copyOf(daily);
        shops = List.copyOf(shops);
        topSoldItems = List.copyOf(topSoldItems);
        topBoughtOffers = List.copyOf(topBoughtOffers);
        Objects.requireNonNull(concentration, "concentration");
        Objects.requireNonNull(health, "health");
    }

    /** Per-day economy totals for one trade day with at least one recorded or settled trade. */
    public record DailyTotals(
            LocalDate tradeDay,
            int tradeCount,
            int uniqueTraders,
            long aurMintedItems,
            long aurMintedXp,
            long aurSunkOffers,
            long aurSunkXp,
            long xpPointsSold,
            long xpPointsBought,
            List<Long> revisionIds) {

        public DailyTotals {
            Objects.requireNonNull(tradeDay, "tradeDay");
            revisionIds = List.copyOf(revisionIds);
        }
    }

    /** Per-shop economy totals and budget utilisation in the window. */
    public record ShopTotals(
            Identifier shopId,
            int dailyBudget,
            int tradeCount,
            int uniqueTraders,
            long aurMinted,
            long aurSunk,
            long xpPointsSold,
            long xpPointsBought,
            int playerDaysActive,
            int playerDaysNearCap,
            int playerDaysAtCap,
            int avgBudgetUsedBps,
            int maxPlayerDayAurPaid) {

        public ShopTotals {
            Objects.requireNonNull(shopId, "shopId");
        }
    }

    /** Aggregate volume of one item sold to shops. */
    public record ItemFlow(String item, long count, long aur, int tradeCount) {

        public ItemFlow {
            Objects.requireNonNull(item, "item");
        }
    }

    /** Aggregate volume of one offer (or unkeyed legacy item entry) bought from a shop. */
    public record OfferFlow(
            Identifier shopId,
            Optional<String> offerId,
            String item,
            long count,
            long aur,
            int tradeCount) {

        public OfferFlow {
            Objects.requireNonNull(shopId, "shopId");
            Objects.requireNonNull(offerId, "offerId");
            Objects.requireNonNull(item, "item");
        }
    }

    /** Distribution of Aur minted across characters in the window. */
    public record Concentration(
            long totalAurMinted,
            int uniqueEarners,
            long top5AurMinted,
            long top10PctAurMinted) {}

    /** Global counts of trade operations waiting on settlement or flagged for reconciliation. */
    public record OperationHealth(int recordedCount, int reconcileCount) {}
}
