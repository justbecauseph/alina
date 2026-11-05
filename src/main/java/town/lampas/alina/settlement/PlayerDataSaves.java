package town.lampas.alina.settlement;

import java.nio.file.Path;
import java.util.Objects;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import town.lampas.alina.Alina;
import town.lampas.alina.mixin.PlayerStorageAccess;

/**
 * The verified player-data save (ALINA-PLAN §7). Vanilla catches a failed save and logs it, so a save counts only
 * when the primary {@code playerdata/<uuid>.dat} reads back with the expected escrow in it.
 */
public final class PlayerDataSaves {
    private PlayerDataSaves() {}

    /**
     * Saves the player, then reports whether the primary file holds {@code expected} under the escrow attachment.
     * A null {@code expected} means the file must hold no escrow. Returns false on any failure. Server thread only.
     */
    public static boolean saveAndVerify(MinecraftServer server, ServerPlayer player, String expected) {
        try {
            ((PlayerStorageAccess) server).alina$playerDataStorage().save(player);
            Path file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player.getUUID() + ".dat");
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.defaultQuota());
            String actual = tag.getCompoundOrEmpty(AttachmentTarget.NBT_ATTACHMENT_KEY)
                    .getString(SettlementModule.ESCROW.identifier().toString())
                    .orElse(null);
            return Objects.equals(expected, actual);
        } catch (Exception failure) {
            Alina.LOGGER.warn("Alina could not save and read back the player data of {}", player.getUUID(), failure);
            return false;
        }
    }
}
