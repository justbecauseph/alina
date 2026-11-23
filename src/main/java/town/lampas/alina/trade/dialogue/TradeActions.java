package town.lampas.alina.trade.dialogue;

import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import town.lampas.alina.trade.TradeModule;
import town.lampas.haruhi.dialogue.api.DialogueAction;
import town.lampas.haruhi.dialogue.api.ParseContext;

/** The trade's dialogue action, {@code alina:trade/open}. */
final class TradeActions {
    private static final String OPEN = "alina:trade/open";

    private TradeActions() {}

    /**
     * Opens a shop. The conversation ends first, and the shop opens on the next server tick, so the conversation's close is
     * sent before the screen. A refusal is sent to the player as a chat message.
     */
    static DataResult<DialogueAction> open(JsonObject json, ParseContext context) {
        return TradeArgs.<DialogueAction>parse(context, OPEN, json, Set.of("shop", "then"), args -> {
            Identifier shop = args.shop("shop");
            Optional<Identifier> then = args.graph("then");
            return ctx -> {
                ctx.end();
                TradeModule.service().openNextTick(ctx.player(), shop, ctx.interlocutor(), ctx.anchor(), then);
            };
        });
    }
}
