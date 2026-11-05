package town.lampas.alina.mixin;

import java.util.function.Predicate;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import town.lampas.alina.criteria.CriterionWatchers;

/**
 * Hands every simple criterion trigger event to Alina's watchers (ALINA-PLAN §9.1). At the head of
 * {@code trigger(ServerPlayer, Predicate)}, before vanilla returns early for a player who has no unfinished advancement
 * on this trigger, so commission proofs and achievements see events whatever the player's vanilla advancements.
 */
@Mixin(SimpleCriterionTrigger.class)
abstract class SimpleCriterionTriggerMixin {

    @Inject(method = "trigger", at = @At("HEAD"))
    private void alina$dispatchToWatchers(ServerPlayer player, Predicate<?> predicate, CallbackInfo ci) {
        CriterionWatchers.dispatch((CriterionTrigger<?>) (Object) this, player, predicate);
    }
}
