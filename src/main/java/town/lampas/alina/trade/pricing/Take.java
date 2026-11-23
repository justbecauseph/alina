package town.lampas.alina.trade.pricing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * {@code count} items taken from the main inventory slot {@code slot}: the 36 slots of {@code getNonEquipmentItems()}, never
 * the offhand or the armour.
 *
 * @param slot the inventory index, at least 0
 * @param count the items taken, at least 1
 */
public record Take(int slot, int count) {

    public Take {
        if (slot < 0) {
            throw new IllegalArgumentException("a take names a slot, not " + slot);
        }
        if (count < 1) {
            throw new IllegalArgumentException("a take removes at least 1 item, was " + count);
        }
    }

    /**
     * Removes the takes from the inventory and returns a copy of what each one removed, in take order. Every take is
     * checked first: when a slot holds fewer items than the takes on it, or a take names a slot outside the main
     * inventory, this throws {@link IllegalStateException} and the inventory is unchanged.
     */
    public static List<ItemStack> apply(Inventory inventory, List<Take> takes) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(takes, "takes");
        int main = inventory.getNonEquipmentItems().size();
        Map<Integer, Integer> demand = new HashMap<>();
        for (Take take : takes) {
            if (take.slot() >= main) {
                throw new IllegalStateException("slot " + take.slot() + " is not in the main inventory");
            }
            demand.merge(take.slot(), take.count(), Integer::sum);
        }
        for (Map.Entry<Integer, Integer> entry : demand.entrySet()) {
            int held = inventory.getItem(entry.getKey()).getCount();
            if (held < entry.getValue()) {
                throw new IllegalStateException("slot " + entry.getKey() + " holds " + held + " items, but the takes remove "
                        + entry.getValue());
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
