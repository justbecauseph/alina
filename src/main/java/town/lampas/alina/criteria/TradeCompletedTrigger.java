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
 * {@code alina:trade_completed}, fired when a trade settles (ALINA-PLAN section 6.3). Its conditions are {@code shop} (an
 * id), {@code aur_earned} (integer bounds on the Aur the shop paid the player) and {@code aur_spent} (integer bounds on the
 * Aur the player paid the shop).
 */
public final class TradeCompletedTrigger extends SimpleCriterionTrigger<TradeCompletedTrigger.TriggerInstance> {

    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    /** Fires for a settled trade with {@code shop}: the Aur the shop paid the player and the Aur the player paid it. */
    public void trigger(ServerPlayer player, Identifier shop, int aurEarned, int aurSpent) {
        trigger(player, instance -> instance.matches(shop, aurEarned, aurSpent));
    }

    /** The conditions of one {@code alina:trade_completed} criterion. An absent condition matches every event. */
    public record TriggerInstance(
            Optional<ContextAwarePredicate> player,
            Optional<Identifier> shop,
            Optional<MinMaxBounds.Ints> aurEarned,
            Optional<MinMaxBounds.Ints> aurSpent) implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                Identifier.CODEC.optionalFieldOf("shop").forGetter(TriggerInstance::shop),
                MinMaxBounds.Ints.CODEC.optionalFieldOf("aur_earned").forGetter(TriggerInstance::aurEarned),
                MinMaxBounds.Ints.CODEC.optionalFieldOf("aur_spent").forGetter(TriggerInstance::aurSpent)
        ).apply(instance, TriggerInstance::new));

        boolean matches(Identifier shopId, int earned, int spent) {
            return TriggerConditions.matches(shop, shopId)
                    && TriggerConditions.inBounds(aurEarned, earned)
                    && TriggerConditions.inBounds(aurSpent, spent);
        }
    }
}
