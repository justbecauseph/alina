package town.lampas.alina.trade.dialogue;

import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.Set;
import net.minecraft.resources.Identifier;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.trade.TradeModule;
import town.lampas.alina.trade.pricing.Wallet;
import town.lampas.haruhi.dialogue.api.DialogueCondition;
import town.lampas.haruhi.dialogue.api.ParseContext;

/** The trade's dialogue conditions. Each is side-effect free and reads the player's state when it runs. */
final class TradeConditions {
    private static final String CAN_AFFORD = "alina:trade/can_afford";
    private static final String BUDGET_LEFT = "alina:trade/budget_left";

    private TradeConditions() {}

    /** True when the player holds at least {@code value} Aur in the main inventory. */
    static DataResult<DialogueCondition> canAfford(JsonObject json, ParseContext context) {
        return TradeArgs.<DialogueCondition>parse(context, CAN_AFFORD, json, Set.of("value"), args -> {
            int value = args.nonNegative("value");
            return ctx -> Wallet.total(ctx.player().getInventory(), AlinaContent.trade().currency()) >= value;
        });
    }

    /**
     * Compares the Aur the shop can still pay the player today. False while the record loads, and for a shop that does not
     * buy.
     */
    static DataResult<DialogueCondition> budgetLeft(JsonObject json, ParseContext context) {
        return TradeArgs.<DialogueCondition>parse(context, BUDGET_LEFT, json, Set.of("shop", "op", "value"), args -> {
            Identifier shop = args.shop("shop");
            Compare compare = Compare.of(args.string("op"));
            int value = args.integer("value");
            return ctx -> TradeModule.service().budget(ctx.player(), shop)
                    .map(budget -> compare.test(budget.left(), value))
                    .orElse(false);
        });
    }

    /** The comparison ops of {@code alina:trade/budget_left}, as haruhi's {@code haruhi:flag} spells them. */
    private enum Compare {
        EQ("eq"), NE("ne"), GT("gt"), GTE("gte"), LT("lt"), LTE("lte");

        private static final String NAMES = "eq, ne, gt, gte, lt, lte";

        private final String id;

        Compare(String id) {
            this.id = id;
        }

        static Compare of(String op) {
            for (Compare compare : values()) {
                if (compare.id.equals(op)) {
                    return compare;
                }
            }
            throw TradeArgs.invalid("'op' must be one of " + NAMES + ", not '" + op + "'");
        }

        boolean test(int actual, int expected) {
            return switch (this) {
                case EQ -> actual == expected;
                case NE -> actual != expected;
                case GT -> actual > expected;
                case GTE -> actual >= expected;
                case LT -> actual < expected;
                case LTE -> actual <= expected;
            };
        }
    }
}
