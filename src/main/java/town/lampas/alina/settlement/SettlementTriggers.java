package town.lampas.alina.settlement;

import java.util.Locale;
import town.lampas.alina.Alina;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.content.Commission;
import town.lampas.alina.criteria.AlinaTriggers;
import town.lampas.alina.storage.Operation;
import town.lampas.alina.storage.OperationKind;

/**
 * Fires Alina's criterion triggers for a settled operation (ALINA-PLAN §6.3): {@code alina:reputation_changed} when the
 * operation changed the player's reputation, and {@code alina:commission_completed} for a turn-in.
 *
 * <p>They fire when the reward has reached the player, once per operation in the running server. A crash after the
 * delivery save and before this listener runs loses the trigger event, never the reward. {@code alina:achievement_earned}
 * is not fired here: the achievement service fires it, because an achievement without a reward is earned without
 * settlement.
 */
public final class SettlementTriggers {
    private SettlementTriggers() {}

    public static void register(SettlementService service) {
        service.addSettledListener(SettlementTriggers::fire);
    }

    static void fire(SettledOperation settled) {
        Operation operation = settled.operation();
        if (operation.reputationDelta() != 0) {
            AlinaTriggers.REPUTATION_CHANGED.trigger(settled.player(), settled.result().reputation());
        }
        if (operation.kind() == OperationKind.TURN_IN) {
            Commission commission = AlinaContent.guild().commissions().get(operation.subjectId());
            if (commission == null) {
                // The active content no longer has the commission, so its category and kind are unknown.
                Alina.LOGGER.debug("Settled turn-in of {} is not in the active content; commission_completed not fired",
                        operation.subjectId());
                return;
            }
            AlinaTriggers.COMMISSION_COMPLETED.trigger(settled.player(), commission.id(), commission.category(),
                    commission.kind().name().toLowerCase(Locale.ROOT));
        }
    }
}
