package town.lampas.alina.criteria;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code alina:manual}, fired by the {@code alina:achievement/trigger} dialogue action so a conversation can count
 * towards an achievement (ALINA-PLAN §6.3). Its condition is {@code key}, a string.
 */
public final class ManualTrigger extends SimpleCriterionTrigger<ManualTrigger.TriggerInstance> {

    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    /** Fires with the key the dialogue action named. */
    public void trigger(ServerPlayer player, String key) {
        trigger(player, instance -> instance.matches(key));
    }

    /** The conditions of one {@code alina:manual} criterion. An absent condition matches every event. */
    public record TriggerInstance(
            Optional<ContextAwarePredicate> player,
            Optional<String> key) implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                Codec.STRING.optionalFieldOf("key").forGetter(TriggerInstance::key)
        ).apply(instance, TriggerInstance::new));

        boolean matches(String keyName) {
            return TriggerConditions.matches(key, keyName);
        }
    }
}
