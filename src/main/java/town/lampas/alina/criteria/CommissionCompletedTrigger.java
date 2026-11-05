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
 * {@code alina:commission_completed}, fired when a turn-in settles (ALINA-PLAN §6.3). Its conditions are
 * {@code commission} (an id), {@code category} (A to F) and {@code kind} ({@code repeatable} or {@code feat}).
 */
public final class CommissionCompletedTrigger
        extends SimpleCriterionTrigger<CommissionCompletedTrigger.TriggerInstance> {

    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    /** Fires for a settled turn-in. {@code category} is the commission's letter and {@code kind} its kind. */
    public void trigger(ServerPlayer player, Identifier commission, char category, String kind) {
        trigger(player, instance -> instance.matches(commission, category, kind));
    }

    /** The conditions of one {@code alina:commission_completed} criterion. An absent condition matches every event. */
    public record TriggerInstance(
            Optional<ContextAwarePredicate> player,
            Optional<Identifier> commission,
            Optional<String> category,
            Optional<String> kind) implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                Identifier.CODEC.optionalFieldOf("commission").forGetter(TriggerInstance::commission),
                TriggerConditions.CATEGORY.optionalFieldOf("category").forGetter(TriggerInstance::category),
                TriggerConditions.KIND.optionalFieldOf("kind").forGetter(TriggerInstance::kind)
        ).apply(instance, TriggerInstance::new));

        boolean matches(Identifier commissionId, char categoryLetter, String kindName) {
            return TriggerConditions.matches(commission, commissionId)
                    && TriggerConditions.matches(category, String.valueOf(categoryLetter))
                    && TriggerConditions.matches(kind, kindName);
        }
    }
}
