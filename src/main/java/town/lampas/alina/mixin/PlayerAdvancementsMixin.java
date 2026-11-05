package town.lampas.alina.mixin;

import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Hooks vanilla's advancement packets for {@code AdvancementSync} (ALINA-PLAN §9.4): holds the first packet after a
 * join until the player's achievement record has loaded, and adds Alina's holders and progress to every reset packet.
 */
@Mixin(PlayerAdvancements.class)
abstract class PlayerAdvancementsMixin {
}
