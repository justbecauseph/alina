package town.lampas.alina.trade.pricing;

import java.util.List;
import java.util.Objects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Whether granted stacks fit in the main inventory, placed the way {@code SettlementService} places a delivery: each stack
 * is cut to its max stack size (and at most 99), then each piece fills the main stacks of the same item and components
 * that have room, lower slots first, and then the empty main slots, lower slots first. The offhand and the armour never
 * take a stack.
 */
public final class InventoryFit {
    /** The largest piece a stack is cut to, as {@code SettlementService} cuts a delivery. */
    private static final int MAX_PIECE = 99;

    private InventoryFit() {}

    /**
     * Whether every granted stack would be placed in full after {@code removed} is taken out of the inventory. Works on
     * copies of the main inventory, so the inventory does not change.
     *
     * @throws IllegalStateException when a take names a slot outside the main inventory, or takes more than the slot holds
     */
    public static boolean fits(Inventory inventory, List<Take> removed, List<ItemStack> granted) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(removed, "removed");
        Objects.requireNonNull(granted, "granted");
        int main = inventory.getNonEquipmentItems().size();
        ItemStack[] slots = new ItemStack[main];
        for (int slot = 0; slot < main; slot++) {
            slots[slot] = inventory.getItem(slot).copy();
        }
        for (Take take : removed) {
            if (take.slot() >= main) {
                throw new IllegalStateException("slot " + take.slot() + " is not in the main inventory");
            }
            if (slots[take.slot()].getCount() < take.count()) {
                throw new IllegalStateException("slot " + take.slot() + " holds " + slots[take.slot()].getCount()
                        + " items, but the take removes " + take.count());
            }
            slots[take.slot()].shrink(take.count());
        }
        for (ItemStack stack : granted) {
            if (stack.isEmpty()) {
                continue;
            }
            int limit = Math.clamp(stack.getMaxStackSize(), 1, MAX_PIECE);
            for (int left = stack.getCount(); left > 0; left -= Math.min(limit, left)) {
                if (!place(slots, stack.copyWithCount(Math.min(limit, left)))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Places the whole piece in the slots, and returns whether it fit. Changes the slot copies it is given. */
    private static boolean place(ItemStack[] slots, ItemStack piece) {
        int left = piece.getCount();
        for (int slot = 0; slot < slots.length && left > 0; slot++) {
            ItemStack existing = slots[slot];
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, piece)) {
                int moved = Math.min(existing.getMaxStackSize() - existing.getCount(), left);
                if (moved > 0) {
                    existing.grow(moved);
                    left -= moved;
                }
            }
        }
        for (int slot = 0; slot < slots.length && left > 0; slot++) {
            if (slots[slot].isEmpty()) {
                int moved = Math.min(piece.getMaxStackSize(), left);
                slots[slot] = piece.copyWithCount(moved);
                left -= moved;
            }
        }
        return left == 0;
    }
}
