package town.lampas.alina.achievement;

import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;
import town.lampas.alina.Alina;
import town.lampas.alina.api.AlinaServices;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.settlement.SettlementModule;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.dialogue.api.DialogueRegistries;

/**
 * Owned by the achievement component (ALINA-PLAN §9). Registers {@code /alina achievement} and the
 * {@code alina:achievement} dialogue condition during {@code onInitialize}, makes one {@link AchievementService} and one
 * {@link AdvancementSync} when the server starts, and drives them from server, player and content events.
 */
public final class AchievementModule {
    /** Server ticks between two logged achievement tick failures. */
    private static final long TICK_ERROR_LOG_TICKS = 100;
    private static volatile AchievementService running;
    private static volatile AdvancementSync syncRunning;
    /** haruhi's activation listener is added once per JVM, on the first server start. */
    private static final AtomicBoolean ACTIVATION_LISTENER = new AtomicBoolean();
    /** Server ticks counted by the tick handler. Read and written on the server thread only. */
    private static long tickCount;
    /** The tick count from which the next service tick failure is logged. */
    private static long nextServiceErrorLog;
    /** The tick count from which the next sync tick failure is logged. */
    private static long nextSyncErrorLog;

    private AchievementModule() {}

    public static void init() {
        AchievementCommands.init();
        DialogueRegistries.registerCondition(Alina.id("achievement"), AchievementConditions::achievement);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            AchievementService service = new AchievementService(server, AlinaServices.storage(),
                    SettlementModule.service());
            AdvancementSync sync = createSync(service, server);
            if (sync != null) {
                service.addListener(sync);
            }
            running = service;
            syncRunning = sync;
            if (ACTIVATION_LISTENER.compareAndSet(false, true)) {
                HaruhiServices.content().addActivationListener(bundle -> {
                    AchievementService current = running;
                    if (current != null) {
                        current.contentActivated();
                    }
                });
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            AchievementService service = running;
            if (service == null) {
                return;
            }
            tickCount++;
            try {
                service.tick();
            } catch (RuntimeException failure) {
                // An achievement bug must not stop the server: the failure is logged, at most once per 100 ticks.
                if (tickCount >= nextServiceErrorLog) {
                    Alina.LOGGER.error("Alina achievements failed on a server tick; the server carries on", failure);
                    nextServiceErrorLog = tickCount + TICK_ERROR_LOG_TICKS;
                }
            }
            AdvancementSync sync = syncRunning;
            if (sync != null) {
                try {
                    sync.tick();
                } catch (RuntimeException failure) {
                    if (tickCount >= nextSyncErrorLog) {
                        Alina.LOGGER.error("Alina advancement sync failed on a server tick; the server carries on",
                                failure);
                        nextSyncErrorLog = tickCount + TICK_ERROR_LOG_TICKS;
                    }
                }
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            AchievementService service = running;
            if (service != null) {
                service.join(handler.getPlayer());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            AchievementService service = running;
            if (service != null) {
                service.leave(handler.getPlayer());
            }
            AdvancementSync sync = syncRunning;
            if (sync != null) {
                sync.leave(handler.getPlayer());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            AchievementService service = running;
            if (service != null) {
                service.stop();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            running = null;
            syncRunning = null;
        });
    }

    /** The running server's achievement service. Throws before the server has started. */
    public static AchievementService service() {
        AchievementService service = running;
        if (service == null) {
            throw new IllegalStateException("Alina achievements are not running; the server has not started");
        }
        return service;
    }

    /** The running server's advancement sync, or null when no server is running. For the mixin. */
    public static @Nullable AdvancementSync sync() {
        return syncRunning;
    }

    /**
     * The advancement sync of the server. Its constructor is not built yet in every worktree; a failure is logged and the
     * server runs without the sync, so achievements still count and reward.
     */
    private static @Nullable AdvancementSync createSync(AchievementService service, MinecraftServer server) {
        try {
            return new AdvancementSync(service::view, AlinaContent::achievements,
                    () -> server.getPlayerList().getPlayers());
        } catch (RuntimeException failure) {
            Alina.LOGGER.error("Alina could not start the advancement sync; achievements are not shown in the advancement "
                    + "screen", failure);
            return null;
        }
    }
}
