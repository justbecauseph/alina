package town.lampas.alina.guild.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.guild.BoardView;
import town.lampas.alina.guild.CommissionView;
import town.lampas.alina.guild.GuildModule;
import town.lampas.alina.guild.GuildService;
import town.lampas.alina.guild.GuildText;
import town.lampas.alina.guild.GuildView;
import town.lampas.alina.guild.Refusal;

/**
 * {@code /alina guild}, for gamemasters (ALINA-PLAN §8.4). Feedback is {@link Component#literal} text. The commands that act
 * on a player answer with the service's refusal when the player's record is not loaded.
 */
public final class GuildCommands {
    private static final Predicate<CommandSourceStack> GAMEMASTERS =
            Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    private GuildCommands() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register(GuildCommands::register);
    }

    private static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext buildContext,
            Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("alina")
                .then(Commands.literal("guild")
                        .requires(GAMEMASTERS)
                        .then(Commands.literal("board").executes(GuildCommands::board))
                        .then(Commands.literal("show")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(GuildCommands::show)))
                        .then(operation("accept", "Accepted", (service, player, commission) ->
                                service.accept(player, commission)))
                        .then(operation("turn_in", "Turned in", (service, player, commission) ->
                                service.turnIn(player, commission)))
                        .then(operation("abandon", "Abandoned", (service, player, commission) ->
                                service.abandon(player, commission)))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(commissionArgument().executes(GuildCommands::reset))))
                        .then(Commands.literal("reputation")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("value", IntegerArgumentType.integer())
                                                .executes(GuildCommands::reputation))))));
    }

    private static int board(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Optional<BoardView> board = GuildModule.service().board();
        if (board.isEmpty()) {
            source.sendFailure(Component.literal("Today's board is still loading."));
            return 0;
        }
        BoardView view = board.get();
        String ids = view.commissions().isEmpty()
                ? "none"
                : String.join(", ", view.commissions().stream().map(Identifier::toString).toList());
        String stored = view.stored() ? "" : " (not stored)";
        source.sendSuccess(() -> Component.literal("Board " + view.date() + ", revision " + view.revisionId()
                + stored + ": " + ids), false);
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        CommandSourceStack source = context.getSource();
        GuildService service = GuildModule.service();
        Optional<GuildView> loaded = service.view(player);
        if (loaded.isEmpty()) {
            source.sendFailure(Component.literal(player.getName().getString() + "'s Guild record is still loading."));
            return 0;
        }
        GuildView view = loaded.get();
        source.sendSuccess(() -> Component.literal(player.getName().getString() + " reputation " + view.reputation()),
                false);
        view.commissions().values().stream()
                .sorted(Comparator.comparing((CommissionView commission) -> commission.id().toString()))
                .forEach(commission -> source.sendSuccess(() -> Component.literal(describe(service, commission)),
                        false));
        return 1;
    }

    /** One line for a commission: its id, standing and progress, and the time left on a cooldown. */
    private static String describe(GuildService service, CommissionView commission) {
        StringBuilder line = new StringBuilder(commission.id() + ": " + commission.standing());
        line.append(", progress ").append(commission.progress());
        commission.cooldownUntil().ifPresent(until ->
                line.append(", cooldown left ").append(GuildText.duration(Duration.between(service.now(), until))));
        return line.toString();
    }

    private static int reputation(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        int value = IntegerArgumentType.getInteger(context, "value");
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        GuildModule.service().setReputation(player, value).whenComplete((ignored, failure) -> server.execute(() -> {
            if (failure != null) {
                source.sendFailure(Component.literal("Could not set the reputation: " + failure.getMessage()));
            } else {
                source.sendSuccess(() -> Component.literal("Set the reputation of " + player.getName().getString()
                        + " to " + value), true);
            }
        }));
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        Identifier commission = IdentifierArgument.getId(context, "commission");
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        CompletableFuture<Optional<Refusal>> reset = GuildModule.service().reset(player, commission);
        reset.whenComplete((refusal, failure) -> server.execute(() -> {
            if (failure != null) {
                source.sendFailure(Component.literal("Could not reset " + commission + ": " + failure.getMessage()));
            } else if (refusal.isPresent()) {
                source.sendFailure(refusal.get().message());
            } else {
                source.sendSuccess(() -> Component.literal("Reset " + commission + " for "
                        + player.getName().getString()), true);
            }
        }));
        return 1;
    }

    /** One of the service's operations on a commission. */
    @FunctionalInterface
    private interface Operation {
        Optional<Refusal> run(GuildService service, ServerPlayer player, Identifier commission);
    }

    /** {@code /alina guild <name> <player> <commission>}: runs the operation and reports the refusal or the success. */
    private static LiteralArgumentBuilder<CommandSourceStack> operation(String name, String done, Operation operation) {
        return Commands.literal(name)
                .then(Commands.argument("player", EntityArgument.player())
                        .then(commissionArgument().executes(context -> {
                            ServerPlayer player = EntityArgument.getPlayer(context, "player");
                            Identifier commission = IdentifierArgument.getId(context, "commission");
                            Optional<Refusal> refusal = operation.run(GuildModule.service(), player, commission);
                            CommandSourceStack source = context.getSource();
                            if (refusal.isPresent()) {
                                source.sendFailure(refusal.get().message());
                                return 0;
                            }
                            source.sendSuccess(() -> Component.literal(done + " " + commission + " for "
                                    + player.getName().getString()), true);
                            return 1;
                        })));
    }

    /** The commission argument, which suggests the ids of the active content's commissions. */
    private static RequiredArgumentBuilder<CommandSourceStack, Identifier> commissionArgument() {
        return Commands.argument("commission", IdentifierArgument.id())
                .suggests(GuildCommands::suggestCommissions);
    }

    private static CompletableFuture<Suggestions> suggestCommissions(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(AlinaContent.guild().commissions().keySet(), builder);
    }
}
