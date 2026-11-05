package town.lampas.alina.criteria;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code alina:volume_entered}, fired when a player enters a content volume (ALINA-PLAN §6.3). Its condition is
 * {@code volume} (an id).
 */
public final class VolumeEnteredTrigger extends SimpleCriterionTrigger<VolumeEnteredTrigger.TriggerInstance> {

    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    /** Fires when the player enters the volume with this id. */
    public void trigger(ServerPlayer player, Identifier volume) {
        trigger(player, instance -> instance.matches(volume));
    }

    /** The conditions of one {@code alina:volume_entered} criterion. An absent condition matches every event. */
    public record TriggerInstance(
            Optional<ContextAwarePredicate> player,
            Optional<Identifier> volume) implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                Identifier.CODEC.optionalFieldOf("volume").forGetter(TriggerInstance::volume)
        ).apply(instance, TriggerInstance::new));

        boolean matches(Identifier volumeId) {
            return TriggerConditions.matches(volume, volumeId);
        }
    }
}
