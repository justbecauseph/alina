package town.lampas.alina.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import town.lampas.alina.achievement.AchievementModule;
import town.lampas.alina.achievement.AdvancementSync;

/**
 * Hooks vanilla's advancement packets for {@code AdvancementSync} (ALINA-PLAN §9.4). {@code ServerPlayer.tick} calls
 * {@code PlayerAdvancements.flushDirty} once a tick, and that flush sends the join's first packet. At the head of the
 * flush, the first packet waits while the sync holds it. Where the flush builds its packet, Alina's holders and progress
 * join a reset packet. Both hooks do nothing while no server runs the achievements.
 */
@Mixin(PlayerAdvancements.class)
abstract class PlayerAdvancementsMixin {

    /** vanilla's {@code isFirstPacket}: true until the first flush of the join has run. */
    @Shadow
    private boolean isFirstPacket;

    @Inject(method = "flushDirty", at = @At("HEAD"), cancellable = true)
    private void alina$holdFirstPacket(ServerPlayer player, boolean showAdvancements, CallbackInfo ci) {
        if (!isFirstPacket) {
            return;
        }
        AdvancementSync sync = AchievementModule.sync();
        if (sync != null && sync.holdFirstPacket(player)) {
            ci.cancel();
        }
    }

    /**
     * Builds vanilla's packet from a list and a map that hold Alina's holders and progress too, when the packet has the
     * reset flag. The packet copies its collections, so the list and the map are made before it is built. Mixin reads a
     * {@code NEW} target as the constructor's descriptor with the built class as its return type, which is why the target
     * ends in the packet's class rather than in {@code <init>}.
     */
    @WrapOperation(method = "flushDirty", at = @At(value = "NEW",
            target = "(ZLjava/util/Collection;Ljava/util/Set;Ljava/util/Map;Z)"
                    + "Lnet/minecraft/network/protocol/game/ClientboundUpdateAdvancementsPacket;"))
    private ClientboundUpdateAdvancementsPacket alina$addAchievements(boolean reset,
            Collection<AdvancementHolder> added, Set<Identifier> removed,
            Map<Identifier, AdvancementProgress> progress, boolean showAdvancements,
            Operation<ClientboundUpdateAdvancementsPacket> original, ServerPlayer player) {
        AdvancementSync sync = AchievementModule.sync();
        if (!reset || sync == null) {
            return original.call(reset, added, removed, progress, showAdvancements);
        }
        List<AdvancementHolder> holders = new ArrayList<>(added);
        Map<Identifier, AdvancementProgress> map = new HashMap<>(progress);
        sync.appendToReset(player, holders, map);
        return original.call(reset, holders, removed, map, showAdvancements);
    }
}
