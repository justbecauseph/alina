package town.lampas.alina.criteria;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.MinMaxBounds;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code alina:reputation_changed}, fired when a player's reputation changes (ALINA-PLAN §6.3). Its condition is
 * {@code reputation}, integer bounds on the new value.
 */
public final class ReputationChangedTrigger
        extends SimpleCriterionTrigger<ReputationChangedTrigger.TriggerInstance> {

    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    /** Fires with the player's reputation after the change. */
    public void trigger(ServerPlayer player, int reputation) {
        trigger(player, instance -> instance.matches(reputation));
    }

    /** The conditions of one {@code alina:reputation_changed} criterion. An absent condition matches every event. */
    public record TriggerInstance(
            Optional<ContextAwarePredicate> player,
            Optional<MinMaxBounds.Ints> reputation) implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                MinMaxBounds.Ints.CODEC.optionalFieldOf("reputation").forGetter(TriggerInstance::reputation)
        ).apply(instance, TriggerInstance::new));

        boolean matches(int value) {
            return TriggerConditions.inBounds(reputation, value);
        }
    }
}
