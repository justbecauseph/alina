package town.lampas.alina.settlement;

import town.lampas.alina.Alina;
import town.lampas.alina.criteria.AlinaTriggers;

/**
 * Fires Alina's criterion triggers for settled operations (ALINA-PLAN section 6.3). {@link #register} is the one entry
 * point, and {@link SettlementModule} calls it when the server starts.
 *
 * <ul>
 *   <li>{@code alina:reputation_changed} fires when a verified save first records the storage result, for an operation
 *       whose reputation delta is not zero. It carries the reputation after the change, from the storage result. The
 *       reward may still be pending then.
 *   <li>{@code alina:commission_completed} fires when the reward has reached the player, for a turn-in whose request
 *       named its commission's category and kind. It carries the commission, the category and the kind, as the request
 *       named them, so the content active at that time does not matter. A turn-in without them fires nothing, and its
 *       reward is still delivered.
 * </ul>
 *
 * <p>A crash between a save and the listener loses the trigger event, never the reward.
 * {@code alina:achievement_earned} is not fired here: the achievement service fires it, because an achievement without a
 * reward is earned without settlement.
 */
public final class SettlementTriggers {
    private SettlementTriggers() {}

    /** Attaches the triggers to the service's applied and settled listeners. */
    public static void register(SettlementService service) {
        service.addAppliedListener(SettlementTriggers::reputationChanged);
        service.addSettledListener(SettlementTriggers::commissionCompleted);
    }

    static void reputationChanged(SettledOperation settled) {
        if (settled.operation().reputationDelta() != 0) {
            AlinaTriggers.REPUTATION_CHANGED.trigger(settled.player(), settled.result().reputation());
        }
    }

    static void commissionCompleted(SettledOperation settled) {
        if (settled.commissionCategory().isEmpty() || settled.commissionKind().isEmpty()) {
            Alina.LOGGER.debug("Settled operation {} has no commission category or kind; commission_completed not fired",
                    settled.operation().operationId());
            return;
        }
        AlinaTriggers.COMMISSION_COMPLETED.trigger(settled.player(), settled.operation().subjectId(),
                settled.commissionCategory().get(), settled.commissionKind().get());
    }
}
