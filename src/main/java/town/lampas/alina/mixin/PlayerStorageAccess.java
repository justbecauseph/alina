package town.lampas.alina.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the server's {@code playerDataStorage} to settlement (ALINA-PLAN §7), which saves player data through it.
 * Cast the server to this interface to call the accessor.
 */
@Mixin(MinecraftServer.class)
public interface PlayerStorageAccess {
    @Accessor("playerDataStorage")
    PlayerDataStorage alina$playerDataStorage();
}
