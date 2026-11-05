package town.lampas.alina.guild.proof;

import java.util.Optional;
import net.minecraft.world.entity.player.Inventory;
import town.lampas.alina.content.Proof;

/**
 * Plans an item proof before anything is removed (ALINA-PLAN §6.1, §8.3).
 *
 * <p>Searched slots, in this order: the main inventory and hotbar ({@code 0} to {@code Inventory.INVENTORY_SIZE - 1})
 * and the off-hand ({@code Inventory.SLOT_OFFHAND}). Armour, the stack carried on the cursor and the contents of items
 * such as shulker boxes and bundles are not searched. A stack matches an entry when the entry's item predicate and
 * every one of its checks pass on it.
 *
 * <p>{@code all_of}: every entry must be met at once, and one item never counts for two entries; a stack may be split
 * between entries. The planner treats it as a flow from slots to entries, each slot supplying its count and each entry
 * needing its {@code count}, and returns a plan when every entry is met. Augmenting paths are tried with entries in
 * content order and slots in search order, so the same inventory always gives the same plan.
 *
 * <p>{@code any_of}: the first entry, in content order, whose matching slots hold at least its count. Its takes come
 * from its matching slots in search order.
 */
public final class ItemProofPlanner {

    private ItemProofPlanner() {}

    /** The plan that meets the proof from this inventory, or empty when the proof is not met. Changes nothing. */
    public static Optional<ItemPlan> plan(Inventory inventory, Proof.Items proof) {
        throw new UnsupportedOperationException("not built yet");
    }
}
