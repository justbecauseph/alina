package town.lampas.alina.content;

import net.minecraft.advancements.predicates.MinMaxBounds;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.Instrument;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Bees;
import net.minecraft.world.item.component.InstrumentComponent;
import net.minecraft.world.item.component.SuspiciousStewEffects;

/** A test of item component data that vanilla's item predicate cannot express (ALINA-PLAN §6.1). */
public sealed interface ItemCheck {
    Identifier BEES = Identifier.fromNamespaceAndPath("alina", "bees");
    Identifier STEW_EFFECT = Identifier.fromNamespaceAndPath("alina", "stew_effect");
    Identifier INSTRUMENT_TAG = Identifier.fromNamespaceAndPath("alina", "instrument_tag");

    boolean test(ItemStack stack);

    /** The number of bees stored in the item ({@code minecraft:bees}) is within {@code count}. */
    record BeeCount(MinMaxBounds.Ints count) implements ItemCheck {
        @Override
        public boolean test(ItemStack stack) {
            Bees bees = stack.getOrDefault(DataComponents.BEES, Bees.EMPTY);
            return count.matches(bees.bees().size());
        }
    }

    /** The suspicious stew's effects ({@code minecraft:suspicious_stew_effects}) include {@code effect}. */
    record StewEffect(Holder<MobEffect> effect) implements ItemCheck {
        @Override
        public boolean test(ItemStack stack) {
            SuspiciousStewEffects effects =
                    stack.getOrDefault(DataComponents.SUSPICIOUS_STEW_EFFECTS, SuspiciousStewEffects.EMPTY);
            // MobEffect is a static registry, so each effect has one instance.
            return effects.effects().stream().anyMatch(entry -> entry.effect().value() == effect.value());
        }
    }

    /** The item's instrument ({@code minecraft:instrument}) is in {@code tag}. */
    record InstrumentTag(TagKey<Instrument> tag) implements ItemCheck {
        @Override
        public boolean test(ItemStack stack) {
            InstrumentComponent instrument = stack.get(DataComponents.INSTRUMENT);
            return instrument != null && instrument.instrument().is(tag);
        }
    }
}
