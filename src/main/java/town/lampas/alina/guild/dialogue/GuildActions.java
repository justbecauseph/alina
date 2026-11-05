package town.lampas.alina.guild.dialogue;

import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import town.lampas.alina.criteria.AlinaTriggers;
import town.lampas.alina.guild.GuildModule;
import town.lampas.alina.guild.GuildService;
import town.lampas.alina.guild.Refusal;
import town.lampas.haruhi.dialogue.api.DialogueAction;
import town.lampas.haruhi.dialogue.api.ParseContext;

/**
 * The Guild's dialogue actions (ALINA-PLAN §8.4): accept, turn in and abandon one named commission, and fire
 * {@code alina:manual} for a conversation. A refused operation is sent to the player as a system message.
 */
final class GuildActions {
    private static final String ACCEPT = "alina:guild/accept";
    private static final String TURN_IN = "alina:guild/turn_in";
    private static final String ABANDON = "alina:guild/abandon";
    private static final String TRIGGER = "alina:achievement/trigger";

    /** One Guild operation on a character's commission. */
    @FunctionalInterface
    interface Operation {
        Optional<Refusal> run(GuildService service, ServerPlayer player, Identifier commission);
    }

    static final Operation ACCEPT_OPERATION = (service, player, commission) -> service.accept(player, commission);
    static final Operation TURN_IN_OPERATION = (service, player, commission) -> service.turnIn(player, commission);
    static final Operation ABANDON_OPERATION = (service, player, commission) -> service.abandon(player, commission);

    private GuildActions() {}

    static DataResult<DialogueAction> accept(JsonObject json, ParseContext context) {
        return operation(ACCEPT, ACCEPT_OPERATION, json, context);
    }

    static DataResult<DialogueAction> turnIn(JsonObject json, ParseContext context) {
        return operation(TURN_IN, TURN_IN_OPERATION, json, context);
    }

    static DataResult<DialogueAction> abandon(JsonObject json, ParseContext context) {
        return operation(ABANDON, ABANDON_OPERATION, json, context);
    }

    static DataResult<DialogueAction> achievementTrigger(JsonObject json, ParseContext context) {
        return DialogueArgs.<DialogueAction>parse(context, TRIGGER, json, Set.of("key"), args -> {
            String key = args.string("key");
            return ctx -> AlinaTriggers.MANUAL.trigger(ctx.player(), key);
        });
    }

    private static DataResult<DialogueAction> operation(
            String type, Operation operation, JsonObject json, ParseContext context) {
        return DialogueArgs.<DialogueAction>parse(context, type, json, Set.of("commission"), args ->
                run(operation, args.commission("commission")));
    }

    /** Runs the operation for the player the conversation is with, and sends a refusal to that player. */
    static DialogueAction run(Operation operation, Identifier commission) {
        return ctx -> operation.run(GuildModule.service(), ctx.player(), commission)
                .ifPresent(refusal -> ctx.player().sendSystemMessage(refusal.message()));
    }
}
