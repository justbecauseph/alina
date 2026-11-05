package town.lampas.alina.guild;

import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
    private static volatile GuildService running;
    /** haruhi's activation listener is added once per JVM, on the first server start. */
    private static final AtomicBoolean ACTIVATION_LISTENER = new AtomicBoolean();

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
                service.tick();
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
