package town.lampas.alina.criteria;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.MinMaxBounds;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code alina:achievement_earned}, fired when an Alina achievement or one of its tiers is earned (ALINA-PLAN §6.3),
 * so an achievement can depend on others. Its conditions are {@code achievement} (an id) and {@code tier} (integer
 * bounds).
 */
public final class AchievementEarnedTrigger extends SimpleCriterionTrigger<AchievementEarnedTrigger.TriggerInstance> {

    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    /** Fires for the earned achievement, with the number of the tier earned. */
    public void trigger(ServerPlayer player, Identifier achievement, int tier) {
        trigger(player, instance -> instance.matches(achievement, tier));
    }

    /** The conditions of one {@code alina:achievement_earned} criterion. An absent condition matches every event. */
    public record TriggerInstance(
            Optional<ContextAwarePredicate> player,
            Optional<Identifier> achievement,
            Optional<MinMaxBounds.Ints> tier) implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                Identifier.CODEC.optionalFieldOf("achievement").forGetter(TriggerInstance::achievement),
                MinMaxBounds.Ints.CODEC.optionalFieldOf("tier").forGetter(TriggerInstance::tier)
        ).apply(instance, TriggerInstance::new));

        boolean matches(Identifier achievementId, int tierNumber) {
            return TriggerConditions.matches(achievement, achievementId)
                    && TriggerConditions.inBounds(tier, tierNumber);
        }
    }
}
