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
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.content.Board;
import town.lampas.alina.guild.BoardView;
import town.lampas.alina.guild.CommissionView;
import town.lampas.alina.guild.GuildModule;
import town.lampas.alina.guild.GuildService;
import town.lampas.alina.guild.GuildText;
import town.lampas.alina.guild.GuildView;
import town.lampas.alina.guild.Refusal;
import town.lampas.alina.guild.block.NoticeBoardBlockEntity;

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
        // The root is gated too. Brigadier merges the alina registrations of Alina's modules into one node that keeps
        // the requirement of whichever registered first, and a player would otherwise be offered an empty /alina.
        dispatcher.register(Commands.literal("alina")
                .requires(GAMEMASTERS)
                .then(Commands.literal("guild")
                        .requires(GAMEMASTERS)
                        .then(Commands.literal("board")
                                .executes(GuildCommands::boards)
                                .then(Commands.argument("board", IdentifierArgument.id())
                                        .suggests(GuildCommands::suggestBoards)
                                        .executes(GuildCommands::board)))
                        .then(Commands.literal("noticeboard")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(GuildCommands::noticeBoard)
                                        .then(Commands.literal("clear").executes(GuildCommands::noticeBoardClear))
                                        .then(Commands.argument("board", IdentifierArgument.id())
                                                .suggests(GuildCommands::suggestBoards)
                                                .executes(GuildCommands::noticeBoardSet))))
                        .then(Commands.literal("show")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(GuildCommands::show)))
                        .then(operation("accept", "Started accepting", (service, player, commission) ->
                                service.accept(player, commission)))
                        .then(operation("turn_in", "Started turning in", (service, player, commission) ->
                                service.turnIn(player, commission)))
                        .then(operation("abandon", "Started abandoning", (service, player, commission) ->
                                service.abandon(player, commission)))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(commissionArgument().executes(GuildCommands::reset))))
                        .then(Commands.literal("reputation")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("value", IntegerArgumentType.integer())
                                                .executes(GuildCommands::reputation))))));
    }

    /** {@code /alina guild board}: every board of the active content, with today's draw. */
    private static int boards(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        GuildService service = GuildModule.service();
        for (Board board : service.content().boards().values()) {
            source.sendSuccess(() -> Component.literal(boardLine(board, service.board(board.id()))), false);
        }
        return service.content().boards().size();
    }

    /** {@code /alina guild board <board>}: one board of the active content, with today's draw. */
    private static int board(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        GuildService service = GuildModule.service();
        Identifier id = IdentifierArgument.getId(context, "board");
        Optional<Board> board = Optional.ofNullable(service.content().boards().get(id));
        if (board.isEmpty()) {
            source.sendFailure(Component.literal("The active content has no board " + id + "."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(boardLine(board.get(), service.board(id))), false);
        return 1;
    }

    /**
     * One board's line: its name, with its id when the name is not the id, then today's date and revision and its
     * commissions, or that it is still loading.
     */
    private static String boardLine(Board board, Optional<BoardView> today) {
        String name = board.name().getString();
        String label = name.equals(board.id().toString()) ? name : name + " (" + board.id() + ")";
        if (today.isEmpty()) {
            return label + ": still loading";
        }
        BoardView view = today.get();
        String ids = view.commissions().isEmpty()
                ? "none"
                : String.join(", ", view.commissions().stream().map(Identifier::toString).toList());
        String stored = view.stored() ? "" : " (not stored)";
        return label + ", " + view.date() + ", revision " + view.revisionId() + stored + ": " + ids;
    }

    /** {@code /alina guild noticeboard <pos>}: the board the Notice Board at the position shows. */
    private static int noticeBoard(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Optional<NoticeBoardBlockEntity> entity = noticeBoardAt(context);
        if (entity.isEmpty()) {
            return 0;
        }
        String at = coordinates(context);
        String assigned = entity.get().board().map(id -> "board " + id).orElse("no board");
        source.sendSuccess(() -> Component.literal("The Notice Board at " + at + " shows " + assigned + "."), false);
        return 1;
    }

    /** {@code /alina guild noticeboard <pos> <board>}: makes the Notice Board at the position open the board. */
    private static int noticeBoardSet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Optional<NoticeBoardBlockEntity> entity = noticeBoardAt(context);
        if (entity.isEmpty()) {
            return 0;
        }
        Identifier id = IdentifierArgument.getId(context, "board");
        if (!GuildModule.service().content().boards().containsKey(id)) {
            source.sendFailure(Component.literal("The active content has no board " + id + "."));
            return 0;
        }
        entity.get().setBoard(Optional.of(id));
        String at = coordinates(context);
        source.sendSuccess(() -> Component.literal("The Notice Board at " + at + " now opens board " + id + "."), true);
        return 1;
    }

    /** {@code /alina guild noticeboard <pos> clear}: removes the board the Notice Board at the position opens. */
    private static int noticeBoardClear(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Optional<NoticeBoardBlockEntity> entity = noticeBoardAt(context);
        if (entity.isEmpty()) {
            return 0;
        }
        entity.get().setBoard(Optional.empty());
        String at = coordinates(context);
        source.sendSuccess(() -> Component.literal("The Notice Board at " + at + " has no board now."), true);
        return 1;
    }

    /** The Notice Board at the {@code pos} argument, or empty after a failure message to the source. */
    private static Optional<NoticeBoardBlockEntity> noticeBoardAt(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        ServerLevel level = context.getSource().getLevel();
        if (level.getBlockEntity(pos) instanceof NoticeBoardBlockEntity entity) {
            return Optional.of(entity);
        }
        context.getSource().sendFailure(Component.literal("There is no Notice Board at " + coordinates(context) + "."));
        return Optional.empty();
    }

    private static String coordinates(CommandContext<CommandSourceStack> context) {
        return coordinates(BlockPosArgument.getBlockPos(context, "pos"));
    }

    private static String coordinates(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
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

    /**
     * {@code /alina guild <name> <player> <commission>}: runs the operation and reports the refusal, or that it started.
     * The storage write is still in flight when the operation returns, and a failed write is reported only to the
     * player, so the source is not told that the operation succeeded.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> operation(
            String name, String started, Operation operation) {
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
                            source.sendSuccess(() -> Component.literal(started + " " + commission + " for "
                                    + player.getName().getString() + ". The player is told the result."), true);
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

    /** The board argument, which suggests the ids of the active content's boards. */
    private static CompletableFuture<Suggestions> suggestBoards(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(AlinaContent.guild().boards().keySet(), builder);
    }
}
