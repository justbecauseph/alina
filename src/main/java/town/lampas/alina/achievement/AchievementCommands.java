package town.lampas.alina.achievement;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.Comparator;
import java.util.OptionalInt;
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
import town.lampas.alina.content.AchievementsContent;
import town.lampas.alina.content.AchievementsContent.Achievement;
import town.lampas.alina.content.AlinaContent;

/**
 * {@code /alina achievement}, for gamemasters (ALINA-PLAN §9.3): {@code list <player>}, {@code grant <player> <achievement>
 * [tier]} and {@code revoke <player> <achievement> [tier]}. Feedback is {@link Component#literal} text.
 */
public final class AchievementCommands {
    private static final Predicate<CommandSourceStack> GAMEMASTERS =
            Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    private AchievementCommands() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register(AchievementCommands::register);
    }

    private static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext buildContext,
            Commands.CommandSelection selection) {
        // The root is gated too, as in the Guild's commands: Brigadier keeps the requirement of whichever registration came
        // first, and a player would otherwise be offered an empty /alina.
        dispatcher.register(Commands.literal("alina")
                .requires(GAMEMASTERS)
                .then(Commands.literal("achievement")
                        .requires(GAMEMASTERS)
                        .then(Commands.literal("list")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(AchievementCommands::list)))
                        .then(staffOperation("grant", AchievementCommands::grant))
                        .then(staffOperation("revoke", AchievementCommands::revoke))));
    }

    /** A staff operation on a player's achievement, with an optional tier: {@code <name> <player> <achievement> [tier]}. */
    private static LiteralArgumentBuilder<CommandSourceStack> staffOperation(String name, Operation operation) {
        return Commands.literal(name)
                .then(Commands.argument("player", EntityArgument.player())
                        .then(achievementArgument()
                                .executes(context -> operation.run(context, OptionalInt.empty()))
                                .then(Commands.argument("tier", IntegerArgumentType.integer(1))
                                        .executes(context -> operation.run(context,
                                                OptionalInt.of(IntegerArgumentType.getInteger(context, "tier")))))));
    }

    /** One staff operation of the command: grant or revoke. */
    @FunctionalInterface
    private interface Operation {
        int run(CommandContext<CommandSourceStack> context, OptionalInt tier) throws CommandSyntaxException;
    }

    private static int list(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        CommandSourceStack source = context.getSource();
        AchievementService service = AchievementModule.service();
        Optional<AchievementView> view = service.view(player);
        if (view.isEmpty()) {
            source.sendFailure(Component.literal(player.getName().getString() + "'s achievements are still loading."));
            return 0;
        }
        AchievementsContent content = service.content();
        content.achievements().values().stream()
                .sorted(Comparator.comparing((Achievement achievement) -> achievement.id().toString()))
                .forEach(achievement -> {
                    String line = describe(achievement, view.get().achievements().get(achievement.id()));
                    source.sendSuccess(() -> Component.literal(line), false);
                });
        return 1;
    }

    /** One line for an achievement: what is earned, the earn count, the counts and when it is available again. */
    private static String describe(Achievement achievement, AchievementStanding standing) {
        StringBuilder line = new StringBuilder(achievement.id().toString()).append(": ");
        if (achievement.tiers().isEmpty()) {
            line.append(standing.earnedTiers() > 0 ? "earned" : "not earned");
        } else {
            line.append("tier ").append(standing.earnedTiers()).append(" of ").append(achievement.tiers().size());
        }
        line.append(", earned ").append(standing.earnCount()).append(" times");
        int target = achievement.tiers().isEmpty()
                ? 0
                : achievement.tiers().get(achievement.tiers().size() - 1).count();
        achievement.criteria().forEach((name, spec) -> line.append(", ").append(name).append(' ')
                .append(standing.count(name)).append('/')
                .append(achievement.tiers().isEmpty() ? spec.count() : target));
        standing.availableAt().ifPresent(at -> line.append(", available at ").append(at));
        return line.toString();
    }

    private static int grant(CommandContext<CommandSourceStack> context, OptionalInt tier)
            throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        Identifier achievement = IdentifierArgument.getId(context, "achievement");
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        CompletableFuture<Optional<String>> granted = AchievementModule.service().grant(player, achievement, tier);
        granted.whenComplete((refusal, failure) -> server.execute(() -> {
            if (failure != null) {
                source.sendFailure(Component.literal("Could not grant " + achievement + ": " + failure.getMessage()));
            } else if (refusal.isPresent()) {
                source.sendFailure(Component.literal(refusal.get()));
            } else {
                source.sendSuccess(() -> Component.literal("Granted " + achievement + describeTier(tier) + " to "
                        + player.getName().getString()), true);
            }
        }));
        return 1;
    }

    private static int revoke(CommandContext<CommandSourceStack> context, OptionalInt tier)
            throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        Identifier achievement = IdentifierArgument.getId(context, "achievement");
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        CompletableFuture<Optional<String>> revoked = AchievementModule.service().revoke(player, achievement, tier);
        revoked.whenComplete((refusal, failure) -> server.execute(() -> {
            if (failure != null) {
                source.sendFailure(Component.literal("Could not revoke " + achievement + ": " + failure.getMessage()));
            } else if (refusal.isPresent()) {
                source.sendFailure(Component.literal(refusal.get()));
            } else {
                source.sendSuccess(() -> Component.literal("Revoked " + achievement + describeTier(tier) + " for "
                        + player.getName().getString()), true);
            }
        }));
        return 1;
    }

    private static String describeTier(OptionalInt tier) {
        return tier.isPresent() ? " tier " + tier.getAsInt() : "";
    }

    /** The achievement argument, which suggests the ids of the active content's achievements. */
    private static RequiredArgumentBuilder<CommandSourceStack, Identifier> achievementArgument() {
        return Commands.argument("achievement", IdentifierArgument.id())
                .suggests(AchievementCommands::suggestAchievements);
    }

    private static CompletableFuture<Suggestions> suggestAchievements(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(AlinaContent.achievements().achievements().keySet(), builder);
    }
}
