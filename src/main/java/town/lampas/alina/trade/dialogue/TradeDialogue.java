package town.lampas.alina.trade.dialogue;

import town.lampas.alina.Alina;
import town.lampas.haruhi.dialogue.api.DialogueRegistries;

/**
 * Registers the trade's haruhi actions and conditions: {@code alina:trade/open}, {@code alina:trade/can_afford} and
 * {@code alina:trade/budget_left}. Called from {@code TradeModule.init}.
 */
public final class TradeDialogue {

    private TradeDialogue() {}

    public static void init() {
        DialogueRegistries.registerAction(Alina.id("trade/open"), TradeActions::open);
        DialogueRegistries.registerCondition(Alina.id("trade/can_afford"), TradeConditions::canAfford);
        DialogueRegistries.registerCondition(Alina.id("trade/budget_left"), TradeConditions::budgetLeft);
    }
}
