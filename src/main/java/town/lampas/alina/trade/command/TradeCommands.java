package town.lampas.alina.trade.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.Locale;
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
import net.minecraft.world.item.ItemStack;
import town.lampas.alina.Alina;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.content.TradeContent;
import town.lampas.alina.content.TradeContent.SellRule;
import town.lampas.alina.trade.TradeModule;
import town.lampas.alina.trade.TradeRefusal;
import town.lampas.alina.trade.TradeService;
import town.lampas.alina.trade.pricing.ReserveCurve;
import town.lampas.alina.trade.pricing.ReserveCurve.Reserves;
import town.lampas.alina.trade.pricing.SellPlanner;

/**
 * {@code /alina trade}, for gamemasters: opens a shop for a player, quotes the held item, and shows or resets a player's
 * budget. Feedback is {@link Component#literal} text.
 */
public final class TradeCommands {
    private static final Predicate<CommandSourceStack> GAMEMASTERS =
            Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    private TradeCommands() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register(TradeCommands::register);
    }

    private static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext buildContext,
            Commands.CommandSelection selection) {
        // The root is gated too, as the Guild's is: Brigadier keeps the requirement of whichever alina registration came
        // first, and a player would otherwise be offered an empty /alina.
        dispatcher.register(Commands.literal("alina")
                .requires(GAMEMASTERS)
                .then(Commands.literal("trade")
                        .requires(GAMEMASTERS)
                        .then(Commands.literal("open")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("shop", IdentifierArgument.id())
                                                .suggests(TradeCommands::suggestShops)
                                                .executes(TradeCommands::open))))
                        .then(Commands.literal("quote")
                                .then(Commands.argument("shop", IdentifierArgument.id())
                                        .suggests(TradeCommands::suggestShops)
                                        .executes(TradeCommands::quote)))
                        .then(Commands.literal("market")
                                .executes(TradeCommands::market)
                                .then(Commands.literal("reset")
                                        .executes(TradeCommands::resetMarket))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("points", reserveArgument())
                                                .then(Commands.argument("aur", reserveArgument())
                                                        .executes(TradeCommands::setMarket)))))
                        .then(Commands.literal("budget")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(TradeCommands::budgets)
                                        .then(Commands.literal("reset")
                                                .executes(context -> reset(context, Optional.empty()))
                                                .then(Commands.argument("shop", IdentifierArgument.id())
                                                        .suggests(TradeCommands::suggestShops)
                                                        .executes(context -> reset(context,
                                                                Optional.of(IdentifierArgument.getId(context, "shop"))))))
                                        .then(Commands.argument("shop", IdentifierArgument.id())
                                                .suggests(TradeCommands::suggestShops)
                                                .executes(TradeCommands::budget))))));
    }

    /** {@code /alina trade open <player> <shop>}: opens the shop with no interlocutor and no anchor. */
    private static int open(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        Identifier shop = IdentifierArgument.getId(context, "shop");
        Optional<TradeRefusal> refusal = TradeModule.service().open(player, shop, Optional.empty(), Optional.empty(),
                Optional.empty());
        if (refusal.isPresent()) {
            source.sendFailure(Component.literal(refusal.get().message().getString()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Opened " + shop + " for " + player.getName().getString()), true);
        return 1;
    }

    /**
     * {@code /alina trade quote <shop>}: prices the item the executor holds, and names the rule that matched it by its index
     * in the shop's rules.
     */
    private static int quote(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        Identifier id = IdentifierArgument.getId(context, "shop");
        TradeContent.Shop shop = AlinaContent.trade().shops().get(id);
        if (shop == null) {
            source.sendFailure(Component.literal("The active content has no shop " + id + "."));
            return 0;
        }
        if (shop.selling().isEmpty()) {
            source.sendFailure(Component.literal(id + " does not buy from players."));
            return 0;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            source.sendFailure(Component.literal("Hold the item to quote in your main hand."));
            return 0;
        }
        String name = held.getItem().toString();
        Optional<SellRule> rule = shop.ruleFor(held);
        if (rule.isEmpty()) {
            source.sendSuccess(() -> Component.literal(name + ": no rule of " + id + " matches it."), false);
            return 1;
        }
        int index = shop.selling().get().rules().indexOf(rule.get());
        if (rule.get().refuse()) {
            source.sendSuccess(() -> Component.literal(name + ": rule " + index + " of " + id + " refuses it."), false);
            return 1;
        }
        Optional<SellPlanner.Quote> quote = SellPlanner.quote(shop, AlinaContent.trade().currency(), held);
        if (quote.isEmpty()) {
            source.sendSuccess(() -> Component.literal(name + ": not sold to " + id
                    + ", it is a coin or holds items."), false);
            return 1;
        }
        String junk = rule.get().junk() ? ", junk" : "";
        source.sendSuccess(() -> Component.literal(name + ": rule " + index + " of " + id + " pays "
                + quote.get().aur() + " Aur for " + quote.get().per() + " items" + junk + "."), false);
        return 1;
    }

    /** {@code /alina trade budget <player> <shop>}: the Aur the shop has paid the player today, and its daily budget. */
    private static int budget(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        Identifier shop = IdentifierArgument.getId(context, "shop");
        Optional<TradeService.Budget> budget = TradeModule.service().budget(player, shop);
        if (budget.isEmpty()) {
            source.sendFailure(Component.literal(shop + " does not buy, or " + player.getName().getString()
                    + "'s trade record is still loading."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(describe(shop, budget.get())), false);
        return 1;
    }

    /** {@code /alina trade budget <player>}: the budget of every shop of the active content that buys. */
    private static int budgets(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        TradeService service = TradeModule.service();
        int shown = 0;
        for (TradeContent.Shop shop : AlinaContent.trade().shops().values()) {
            Optional<TradeService.Budget> budget = service.budget(player, shop.id());
            if (budget.isPresent()) {
                String line = describe(shop.id(), budget.get());
                source.sendSuccess(() -> Component.literal(line), false);
                shown++;
            }
        }
        if (shown == 0) {
            source.sendFailure(Component.literal("No shop buys from " + player.getName().getString()
                    + ", or their trade record is still loading."));
        }
        return shown;
    }

    /** {@code /alina trade budget <player> reset [<shop>]}: forgets today's budget rows, of one shop or of every shop. */
    private static int reset(CommandContext<CommandSourceStack> context, Optional<Identifier> shop)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        MinecraftServer server = source.getServer();
        String scope = shop.map(Identifier::toString).orElse("every shop");
        CompletableFuture<Optional<TradeRefusal>> reset = TradeModule.service().resetBudget(player, shop);
        reset.whenComplete((refusal, failure) -> server.execute(() -> {
            if (failure != null) {
                source.sendFailure(Component.literal("Could not reset the budget of " + scope + ": " + failure.getMessage()));
            } else if (refusal.isPresent()) {
                source.sendFailure(Component.literal(refusal.get().message().getString()));
            } else {
                source.sendSuccess(() -> Component.literal("Reset today's budget of " + scope + " for "
                        + player.getName().getString()), true);
            }
        }));
        return 1;
    }

    /** {@code /alina trade market}: the live reserves and their rate, the seed and its base rate, and the last restock day. */
    private static int market(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Optional<TradeService.MarketStatus> status = TradeModule.service().market();
        if (status.isEmpty()) {
            return marketUnavailable(source);
        }
        TradeService.MarketStatus market = status.get();
        source.sendSuccess(() -> Component.literal("Market: " + reserves(market.live()) + ", " + rate(market.live()) + "."),
                false);
        source.sendSuccess(() -> Component.literal("Seed: " + reserves(market.seed()) + ", base rate "
                + rate(market.seed()) + "."), false);
        source.sendSuccess(() -> Component.literal("Last restock: " + market.restockedDay() + "."), false);
        return 1;
    }

    /** {@code /alina trade market reset}: sets the reserves to the seed the content gives. */
    private static int resetMarket(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Optional<TradeService.MarketStatus> status = TradeModule.service().market();
        if (status.isEmpty()) {
            return marketUnavailable(source);
        }
        return writeMarket(source, status.get().seed(), "reset");
    }

    /** {@code /alina trade market set <points> <aur>}: sets both reserves. */
    private static int setMarket(CommandContext<CommandSourceStack> context) {
        long points = LongArgumentType.getLong(context, "points");
        long aur = LongArgumentType.getLong(context, "aur");
        return writeMarket(context.getSource(), new Reserves(points, aur), "set");
    }

    /**
     * Sets the reserves through the service, which writes the difference from the live reserves, so trades in flight are not
     * refused and keep their place. When the write completes, the sender hears the result, and a success is logged once with
     * who made it and the reserves before and after.
     */
    private static int writeMarket(CommandSourceStack source, Reserves target, String verb) {
        TradeService service = TradeModule.service();
        Optional<Reserves> before = service.market().map(TradeService.MarketStatus::live);
        String who = source.getTextName();
        MinecraftServer server = source.getServer();
        service.setMarket(target.points(), target.aur()).whenComplete((refusal, failure) -> server.execute(() -> {
            if (failure != null) {
                source.sendFailure(Component.literal("Could not " + verb + " the experience market: " + failure.getMessage()));
            } else if (refusal.isPresent()) {
                source.sendFailure(Component.literal(refusal.get().message().getString()));
            } else {
                Alina.LOGGER.info("Alina experience market {} by {}: {} before, {} after", verb, who,
                        before.map(TradeCommands::reserves).orElse("unknown"), reserves(target));
                source.sendSuccess(() -> Component.literal("Experience market set to " + reserves(target) + "."), true);
            }
        }));
        return 1;
    }

    /** A whole number from 1 to the largest reserve, for the points and Aur of {@code /alina trade market set}. */
    private static LongArgumentType reserveArgument() {
        return LongArgumentType.longArg(1, ReserveCurve.MAX_RESERVE);
    }

    private static int marketUnavailable(CommandSourceStack source) {
        source.sendFailure(Component.literal(AlinaContent.trade().market().isEmpty()
                ? "The active content has no experience market."
                : "The experience market is still loading."));
        return 0;
    }

    private static String reserves(Reserves reserves) {
        return reserves.points() + " points and " + reserves.aur() + " Aur";
    }

    /** The rate of the reserves, as hundredths of a point per Aur: {@code 51.00 points per Aur}. */
    private static String rate(Reserves reserves) {
        long hundredths = ReserveCurve.rateHundredths(reserves);
        return String.format(Locale.ROOT, "%d.%02d points per Aur", hundredths / 100, hundredths % 100);
    }

    private static String describe(Identifier shop, TradeService.Budget budget) {
        return shop + ": paid " + budget.paid() + " of " + budget.daily() + " Aur today, " + budget.left() + " left.";
    }

    private static CompletableFuture<Suggestions> suggestShops(CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(AlinaContent.trade().shops().keySet(), builder);
    }
}
