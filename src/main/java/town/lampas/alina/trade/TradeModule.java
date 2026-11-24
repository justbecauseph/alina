package town.lampas.alina.trade;

import java.time.Clock;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import town.lampas.alina.Alina;
import town.lampas.alina.achievement.AchievementModule;
import town.lampas.alina.achievement.AchievementStanding;
import town.lampas.alina.api.AlinaServices;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.guild.GuildModule;
import town.lampas.alina.guild.GuildView;
import town.lampas.alina.settlement.SettlementModule;
import town.lampas.alina.trade.command.TradeCommands;
import town.lampas.alina.trade.dialogue.TradeDialogue;
import town.lampas.alina.trade.network.TradeBuyPayload;
import town.lampas.alina.trade.network.TradeDismissPayload;
import town.lampas.alina.trade.network.TradeExperiencePayload;
import town.lampas.alina.trade.network.TradeNetworking;
import town.lampas.alina.trade.network.TradeSellJunkPayload;
import town.lampas.alina.trade.network.TradeSellPayload;
import town.lampas.alina.trade.remote.TradeMethods;
import town.lampas.alina.trade.remote.TradeRpc;
import town.lampas.haruhi.api.HaruhiServices;

/**
 * Owned by the trade component. Registers the trade's payloads, receivers, haruhi dialogue types and {@code /alina trade}
 * during {@code onInitialize}, makes one {@link TradeService} when the server starts, and drives it from server, player
 * and content events.
 */
public final class TradeModule {
    /** Server ticks between two logged trade tick failures. */
    private static final long TICK_ERROR_LOG_TICKS = 100;
    private static volatile TradeService running;
    private static volatile TradeMethods methods;
    /** haruhi's activation listener is added once per JVM, on the first server start. */
    private static final AtomicBoolean ACTIVATION_LISTENER = new AtomicBoolean();
    /** Server ticks counted by the tick handler. Read and written on the server thread only. */
    private static long tickCount;
    /** The tick count from which the next tick failure is logged. */
    private static long nextTickErrorLog;

    private TradeModule() {}

    public static void init() {
        TradeNetworking.registerPayloadTypes();
        registerReceivers();
        TradeDialogue.init();
        TradeCommands.init();
        TradeRpc.register(() -> methods);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            running = new TradeService(server, AlinaServices.storage(), SettlementModule.service(),
                    AlinaContent::trade, () -> AlinaContent.guild().settings(),
                    () -> HaruhiServices.content().activeRevisionId().orElse(0L), Clock.systemUTC(),
                    () -> server.getPlayerList().getPlayers(), new NetworkMessenger(), TradeModule::reputationOf,
                    TradeModule::earned);
            methods = new TradeMethods(server, running, AlinaServices.storage(), AlinaContent::trade,
                    () -> AlinaContent.guild().settings(), Clock.systemUTC());
            if (ACTIVATION_LISTENER.compareAndSet(false, true)) {
                HaruhiServices.content().addActivationListener(bundle -> {
                    TradeService service = running;
                    if (service != null) {
                        service.contentActivated();
                    }
                });
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            TradeService service = running;
            if (service != null) {
                tickCount++;
                try {
                    service.tick();
                } catch (RuntimeException failure) {
                    // A trade bug must not stop the server: the failure is logged, at most once per 100 ticks, and the
                    // next tick runs as usual.
                    if (tickCount >= nextTickErrorLog) {
                        Alina.LOGGER.error("Alina trade failed on a server tick; the server carries on", failure);
                        nextTickErrorLog = tickCount + TICK_ERROR_LOG_TICKS;
                    }
                }
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            TradeService service = running;
            if (service != null) {
                service.join(handler.getPlayer());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            TradeService service = running;
            if (service != null) {
                service.leave(handler.getPlayer());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            TradeService service = running;
            if (service != null) {
                service.stop();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            methods = null;
            running = null;
        });
    }

    /** The running server's trade service. Throws before the server has started. */
    public static TradeService service() {
        TradeService service = running;
        if (service == null) {
            throw new IllegalStateException("Alina trade is not running; the server has not started");
        }
        return service;
    }

    /**
     * Registers the five client to server receivers. Each runs on the server thread: a payload that arrives on the network
     * thread is handed to the server.
     */
    private static void registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(TradeSellPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            onServer(context.server(), () -> service().sell(player, payload.sessionId(), payload.slot(), payload.count(),
                    payload.expectedAur()));
        });
        ServerPlayNetworking.registerGlobalReceiver(TradeSellJunkPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            onServer(context.server(), () -> service().sellJunk(player, payload.sessionId(), payload.expectedAur()));
        });
        ServerPlayNetworking.registerGlobalReceiver(TradeBuyPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            onServer(context.server(), () -> service().buy(player, payload.sessionId(), payload.offerId(), payload.count(),
                    payload.expectedCost()));
        });
        ServerPlayNetworking.registerGlobalReceiver(TradeExperiencePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            onServer(context.server(), () -> service().experience(player, payload.sessionId(), payload.selling(),
                    payload.aur(), payload.expectedPoints()));
        });
        ServerPlayNetworking.registerGlobalReceiver(TradeDismissPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            onServer(context.server(), () -> service().dismiss(player, payload.sessionId()));
        });
    }

    private static void onServer(MinecraftServer server, Runnable task) {
        if (server.isSameThread()) {
            task.run();
        } else {
            server.execute(task);
        }
    }

    /** The player's reputation from the Guild record, empty while it loads. */
    private static OptionalInt reputationOf(ServerPlayer player) {
        Optional<GuildView> view = GuildModule.service().view(player);
        return view.isPresent() ? OptionalInt.of(view.get().reputation()) : OptionalInt.empty();
    }

    /**
     * Whether the player has earned the achievement, as the {@code alina:achievement} condition reads it: once it has been
     * earned in any cycle, or an earn of it is being settled. Empty while the achievement record loads.
     */
    private static Optional<Boolean> earned(ServerPlayer player, Identifier achievement) {
        return AchievementModule.service().view(player).map(view -> {
            AchievementStanding standing = view.achievements().get(achievement);
            return standing != null && standing.everEarned();
        });
    }
}
