package town.lampas.alina.trade;

import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;
import town.lampas.alina.storage.OperationEffect.TradeSettled.Purchase;
import town.lampas.alina.trade.pricing.Take;

/**
 * What one trade request does, worked out from the inventory before anything changes, or the reason it is refused.
 *
 * @param refusal the reason the request is refused; empty when the plan is to be submitted
 * @param line the shop's line the accepted trade answers with: {@code sold} or {@code bought}
 * @param takes the main inventory takes that pay for the trade, or sell the items
 * @param pointsTaken the experience points the player gives up
 * @param grant the stacks the player receives, in delivery order
 * @param pointsGiven the experience points the player receives
 * @param aurPaid the Aur the shop paid the player; counts against the shop's budget
 * @param purchase the offer bought, when its limit is counted
 * @param detail the JSON object the trade log keeps
 * @param marketPoints the change the trade makes to the experience market's points reserve; 0 for any other trade
 * @param marketAur the change the trade makes to the experience market's Aur reserve; 0 for any other trade
 */
record TradePlan(
        Optional<TradeRefusal.Reason> refusal,
        String line,
        List<Take> takes,
        int pointsTaken,
        List<ItemStack> grant,
        int pointsGiven,
        int aurPaid,
        Optional<Purchase> purchase,
        String detail,
        long marketPoints,
        long marketAur) {

    TradePlan {
        takes = List.copyOf(takes);
        grant = List.copyOf(grant);
    }

    /** A plan that changes nothing and is refused for {@code reason}. */
    static TradePlan refuse(TradeRefusal.Reason reason) {
        return new TradePlan(Optional.of(reason), "", List.of(), 0, List.of(), 0, 0, Optional.empty(), "{}", 0, 0);
    }
}
