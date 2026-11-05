package town.lampas.alina.guild.proof;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import town.lampas.alina.content.Proof;
import town.lampas.alina.guild.proof.ItemPlan.Take;

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
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(proof, "proof");
        List<Slot> slots = searched(inventory);
        return proof.allOf() ? allOf(slots, proof.entries()) : anyOf(slots, proof.entries());
    }

    /** A searched slot that holds items: its inventory index and its stack. */
    private record Slot(int index, ItemStack stack) {}

    /** The non-empty slots of the searched part of the inventory, in search order. */
    private static List<Slot> searched(Inventory inventory) {
        List<Slot> slots = new ArrayList<>();
        for (int index = 0; index < Inventory.INVENTORY_SIZE; index++) {
            addIfHolding(slots, inventory, index);
        }
        addIfHolding(slots, inventory, Inventory.SLOT_OFFHAND);
        return slots;
    }

    private static void addIfHolding(List<Slot> slots, Inventory inventory, int index) {
        ItemStack stack = inventory.getItem(index);
        if (!stack.isEmpty()) {
            slots.add(new Slot(index, stack));
        }
    }

    private static boolean matches(Proof.Entry entry, ItemStack stack) {
        return entry.item().test(stack) && entry.checks().stream().allMatch(check -> check.test(stack));
    }

    private static Optional<ItemPlan> allOf(List<Slot> slots, List<Proof.Entry> entries) {
        boolean[][] matches = new boolean[entries.size()][slots.size()];
        for (int entry = 0; entry < entries.size(); entry++) {
            for (int slot = 0; slot < slots.size(); slot++) {
                matches[entry][slot] = matches(entries.get(entry), slots.get(slot).stack());
            }
        }
        int[] supply = new int[slots.size()];
        for (int slot = 0; slot < slots.size(); slot++) {
            supply[slot] = slots.get(slot).stack().getCount();
        }
        Flow flow = new Flow(matches, supply);
        for (int entry = 0; entry < entries.size(); entry++) {
            for (int unit = 0; unit < entries.get(entry).count(); unit++) {
                // A failed search means no assignment meets this entry, so the proof is not met.
                if (!flow.augment(entry, new boolean[entries.size()], new boolean[slots.size()])) {
                    return Optional.empty();
                }
            }
        }
        // An entry without consume counts its items in the flow but takes none of them.
        List<Take> takes = new ArrayList<>();
        for (int slot = 0; slot < slots.size(); slot++) {
            int taken = 0;
            for (int entry = 0; entry < entries.size(); entry++) {
                if (entries.get(entry).consume()) {
                    taken += flow.amount(entry, slot);
                }
            }
            if (taken > 0) {
                takes.add(new Take(slots.get(slot).index(), taken));
            }
        }
        return Optional.of(new ItemPlan(takes));
    }

    private static Optional<ItemPlan> anyOf(List<Slot> slots, List<Proof.Entry> entries) {
        for (Proof.Entry entry : entries) {
            List<Slot> matching = slots.stream().filter(slot -> matches(entry, slot.stack())).toList();
            int held = matching.stream().mapToInt(slot -> slot.stack().getCount()).sum();
            if (held < entry.count()) {
                continue;
            }
            if (!entry.consume()) {
                return Optional.of(new ItemPlan(List.of()));
            }
            List<Take> takes = new ArrayList<>();
            int remaining = entry.count();
            for (Slot slot : matching) {
                if (remaining == 0) {
                    break;
                }
                int count = Math.min(remaining, slot.stack().getCount());
                takes.add(new Take(slot.index(), count));
                remaining -= count;
            }
            return Optional.of(new ItemPlan(takes));
        }
        return Optional.empty();
    }

    /**
     * The assignment of slot items to entries, grown one item at a time by augmenting paths. {@code amount(e, s)} items of
     * slot {@code s} are assigned to entry {@code e}, and a slot never gives out more than its count.
     */
    private static final class Flow {
        private final boolean[][] matches;
        private final int[] supply;
        private final int[] used;
        private final int[][] amounts;

        Flow(boolean[][] matches, int[] supply) {
            this.matches = matches;
            this.supply = supply;
            this.used = new int[supply.length];
            this.amounts = new int[matches.length][supply.length];
        }

        int amount(int entry, int slot) {
            return amounts[entry][slot];
        }

        /**
         * Assigns one more item of a matching slot to {@code entry}. A slot with no spare item gives one of its items to
         * another entry, which takes a spare item elsewhere, along a path that alternates slots and entries. Each entry
         * and slot is visited at most once per search.
         */
        boolean augment(int entry, boolean[] enteredEntries, boolean[] visitedSlots) {
            enteredEntries[entry] = true;
            for (int slot = 0; slot < supply.length; slot++) {
                if (!matches[entry][slot] || visitedSlots[slot]) {
                    continue;
                }
                visitedSlots[slot] = true;
                if (used[slot] < supply[slot]) {
                    amounts[entry][slot]++;
                    used[slot]++;
                    return true;
                }
                for (int other = 0; other < matches.length; other++) {
                    if (amounts[other][slot] > 0 && !enteredEntries[other]
                            && augment(other, enteredEntries, visitedSlots)) {
                        amounts[other][slot]--;
                        amounts[entry][slot]++;
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
