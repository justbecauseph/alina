package town.lampas.alina.settlement;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** {@code /alina settlement}, for gamemasters (ALINA-PLAN §7). Feedback is {@link Component#literal} text. */
public final class SettlementCommands {
    private static final Predicate<CommandSourceStack> GAMEMASTERS =
            Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    private SettlementCommands() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register(SettlementCommands::register);
    }

    private static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext buildContext,
            Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("alina")
                .then(Commands.literal("settlement")
                        .requires(GAMEMASTERS)
                        .then(Commands.literal("pending")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(SettlementCommands::pending)))
                        .then(Commands.literal("reconciliation")
                                .executes(SettlementCommands::reconciliation))));
    }

    /** Each pending operation: its id, kind, subject, tier, whether storage has applied it, and what is still to deliver. */
    private static int pending(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        CommandSourceStack source = context.getSource();
        List<PendingOperation> pending = SettlementModule.service().pendingFor(player);
        if (pending.isEmpty()) {
            source.sendSuccess(() -> Component.literal(player.getName().getString()
                    + " has no pending settlement operations."), false);
            return 0;
        }
        for (PendingOperation operation : pending) {
            String applied = operation.applied().isPresent() ? "storage applied" : "storage not applied";
            String remaining = describe(operation.reward().stream().filter(stack -> !stack.isEmpty()).toList());
            String line = operation.operationId() + " " + operation.kind() + " " + operation.subjectId()
                    + " tier " + operation.tier() + ", " + applied + ", still to deliver: " + remaining;
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** Each operation the service found in an escrow of another world, with the player who holds it. */
    private static int reconciliation(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<Reconciliation> entries = SettlementModule.service().reconciliation();
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No settlement operation needs reconciliation."), false);
            return 0;
        }
        for (Reconciliation entry : entries) {
            PendingOperation operation = entry.operation();
            String line = entry.player() + " " + operation.operationId() + " " + operation.kind() + " "
                    + operation.subjectId() + " tier " + operation.tier() + ", made in world " + operation.worldId();
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static String describe(List<ItemStack> stacks) {
        if (stacks.isEmpty()) {
            return "nothing";
        }
        return String.join(", ", stacks.stream()
                .map(stack -> stack.getCount() + " " + BuiltInRegistries.ITEM.getKey(stack.getItem()))
                .toList());
    }
}
