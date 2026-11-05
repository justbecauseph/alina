package town.lampas.alina.guild.proof;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The exact removals that meet an item proof (ALINA-PLAN §8.3), as {@link ItemProofPlanner} planned them. Entries whose
 * {@code consume} is false count towards the proof and have no takes.
 *
 * @param takes at most one take per slot, in slot order. Empty when nothing is consumed.
 */
public record ItemPlan(List<Take> takes) {

    public ItemPlan {
        takes = List.copyOf(takes);
        for (int i = 1; i < takes.size(); i++) {
            if (takes.get(i).slot() <= takes.get(i - 1).slot()) {
                throw new IllegalArgumentException("takes must be in slot order, with at most one take per slot");
            }
        }
    }

    /** Takes {@code count} items from the inventory slot {@code slot}. */
    public record Take(int slot, int count) {
        public Take {
            if (count < 1) {
                throw new IllegalArgumentException("a take removes at least 1 item, was " + count);
            }
        }
    }

    /**
     * Removes the takes from the inventory and returns a copy of what each take removed, in take order. Checks every
     * take before removing anything: when a slot no longer holds as many items as its take, it throws
     * {@link IllegalStateException} and the inventory is unchanged.
     */
    public List<ItemStack> apply(Inventory inventory) {
        Objects.requireNonNull(inventory, "inventory");
        for (Take take : takes) {
            int held = inventory.getItem(take.slot()).getCount();
            if (held < take.count()) {
                throw new IllegalStateException("slot " + take.slot() + " holds " + held + " items, but the plan takes "
                        + take.count());
            }
        }
        List<ItemStack> removed = new ArrayList<>(takes.size());
        for (Take take : takes) {
            removed.add(inventory.removeItem(take.slot(), take.count()).copy());
        }
        if (!takes.isEmpty()) {
            inventory.setChanged();
        }
        return List.copyOf(removed);
    }
}
