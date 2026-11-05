package town.lampas.alina.content;

import java.util.List;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.resources.Identifier;

/** What a commission needs at turn-in (ALINA-PLAN §6.1). Exactly one proof per commission. */
public sealed interface Proof {

    /**
     * Items in the player's main inventory, hotbar and off-hand. With {@code allOf} every entry must be found, without
     * two entries sharing a stack; otherwise the first entry, in content order, that is found.
     */
    record Items(boolean allOf, List<Entry> entries) implements Proof {
        public Items {
            entries = List.copyOf(entries);
        }
    }

    /** One item entry: an item predicate without {@code count}, Alina's checks, how many, and whether they are taken. */
    record Entry(ItemPredicate item, List<ItemCheck> checks, int count, boolean consume) {
        public Entry {
            checks = List.copyOf(checks);
        }
    }

    /** {@code criterion.count()} matching trigger events since the commission was accepted. */
    record Criterion(CriterionSpec criterion) implements Proof {}

    /** Enter {@code start}, then reach {@code finish} alive without gliding, Slow Falling or a totem in between. */
    record Descent(Identifier start, Identifier finish) implements Proof {}
}
