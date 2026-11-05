package town.lampas.alina.guild;

import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import town.lampas.alina.Alina;
import town.lampas.alina.api.AlinaServices;
import town.lampas.alina.guild.block.GuildBlocks;
import town.lampas.alina.guild.command.GuildCommands;
import town.lampas.alina.guild.dialogue.GuildDialogue;
import town.lampas.alina.settlement.SettlementModule;
import town.lampas.haruhi.api.HaruhiServices;

/**
 * Owned by the guild component (ALINA-PLAN §8). Registers the Notice Board, the Guild's haruhi dialogue types and
 * {@code /alina guild} during {@code onInitialize}, makes one {@link GuildService} when the server starts, and drives it
 * from server, player and content events.
 */
public final class GuildModule {
    /** Server ticks between two logged Guild tick failures. */
    private static final long TICK_ERROR_LOG_TICKS = 100;
    private static volatile GuildService running;
    /** haruhi's activation listener is added once per JVM, on the first server start. */
    private static final AtomicBoolean ACTIVATION_LISTENER = new AtomicBoolean();
    /** Server ticks counted by the tick handler. Read and written on the server thread only. */
    private static long tickCount;
    /** The tick count from which the next tick failure is logged. */
    private static long nextTickErrorLog;

    private GuildModule() {}

    public static void init() {
        GuildBlocks.init();
        GuildDialogue.init();
        GuildCommands.init();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            running = new GuildService(server, AlinaServices.storage(), SettlementModule.service());
            if (ACTIVATION_LISTENER.compareAndSet(false, true)) {
                HaruhiServices.content().addActivationListener(bundle -> {
                    GuildService service = running;
                    if (service != null) {
                        service.contentActivated();
                    }
                });
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            GuildService service = running;
            if (service != null) {
                tickCount++;
                try {
                    service.tick();
                } catch (RuntimeException failure) {
                    // A Guild bug must not stop the server: the failure is logged, at most once per 100 ticks, and the
                    // next tick runs as usual.
                    if (tickCount >= nextTickErrorLog) {
                        Alina.LOGGER.error("Alina Guild failed on a server tick; the server carries on", failure);
                        nextTickErrorLog = tickCount + TICK_ERROR_LOG_TICKS;
                    }
                }
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            GuildService service = running;
            if (service != null) {
                service.join(handler.getPlayer());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            GuildService service = running;
            if (service != null) {
                service.leave(handler.getPlayer());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            GuildService service = running;
            if (service != null) {
                service.stop();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> running = null);
    }

    /** The running server's Guild service. Throws before the server has started. */
    public static GuildService service() {
        GuildService service = running;
        if (service == null) {
            throw new IllegalStateException("Alina Guild is not running; the server has not started");
        }
        return service;
    }
}
