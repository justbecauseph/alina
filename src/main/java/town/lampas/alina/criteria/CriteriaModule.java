package town.lampas.alina.criteria;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * Owned by the criteria component. A player's watches close when the player leaves and every watch closes when the
 * server stops; their owners open them again from the player's state on join.
 */
public final class CriteriaModule {
    private CriteriaModule() {}

    public static void init() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                CriterionWatchers.clear(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> CriterionWatchers.clearAll());
    }
}
