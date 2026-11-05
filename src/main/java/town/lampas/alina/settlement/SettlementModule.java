package town.lampas.alina.settlement;

import com.mojang.serialization.Codec;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.level.storage.LevelResource;
import town.lampas.alina.Alina;
import town.lampas.alina.api.AlinaServices;

/**
 * Owned by the settlement component (ALINA-PLAN §7). Registers the escrow attachment, makes one
 * {@link SettlementService} when the server starts, and ticks it each server tick. The escrow is registered during
 * {@code onInitialize}, so it exists before any player data loads.
 */
public final class SettlementModule {
    /**
     * The player's pending operations, as JSON, or absent when there are none. Persistent and copied on death, so
     * the escrow survives a restart and a death.
     */
    public static final AttachmentType<String> ESCROW = AttachmentRegistry.create(
            Alina.id("settlement"), builder -> builder.persistent(Codec.STRING).copyOnDeath());

    private static volatile SettlementService running;

    private SettlementModule() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            UUID worldId = WorldId.loadOrCreate(server.getWorldPath(LevelResource.ROOT));
            running = new SettlementService(server, AlinaServices.storage(), worldId);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> running = null);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SettlementService service = running;
            if (service != null) {
                service.tick();
            }
        });
    }

    /** The running server's settlement service. Throws before the server has started. */
    public static SettlementService service() {
        SettlementService service = running;
        if (service == null) {
            throw new IllegalStateException("Alina settlement is not running; the server has not started");
        }
        return service;
    }
}
