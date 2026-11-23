package town.lampas.alina.trade.pricing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import town.lampas.alina.content.TradeContent.Denomination;

/**
 * The shop currency in the player's main inventory: its total, which coins pay a cost, and the coins a change is paid
 * in. A coin is a stack of a denomination's item with no component changes, so a renamed or enchanted stack of the same
 * item is not money.
 */
public final class Wallet {
    /** The largest count of one coin stack. {@code ItemStack.CODEC} refuses a count above 99. */
    private static final int MAX_COUNT = 99;

    private Wallet() {}

    /**
     * The coins that pay a cost.
     *
     * @param takes the coins to take from the main inventory, in slot order, one take per slot
     * @param change the Aur owed back, below the value of the last coin taken
     */
    public record Payment(List<Take> takes, int change) {
        public Payment {
            takes = List.copyOf(takes);
            if (change < 0) {
                throw new IllegalArgumentException("change is not negative, was " + change);
            }
        }
    }

    /** Whether {@code stack} is a coin of the currency: its item is a denomination's, and it has no component changes. */
    public static boolean isCurrency(ItemStack stack, List<Denomination> currency) {
        return denominationOf(stack, currency).isPresent();
    }

    /** The Aur value of the coins in the main inventory. */
    public static long total(Inventory inventory, List<Denomination> currency) {
        Objects.requireNonNull(inventory, "inventory");
        long total = 0;
        for (int slot = 0; slot < mainSize(inventory); slot++) {
            ItemStack stack = inventory.getItem(slot);
            total += denominationOf(stack, currency).map(denomination -> (long) denomination.value() * stack.getCount())
                    .orElse(0L);
        }
        return total;
    }

    /**
     * The coins that pay {@code cost} Aur, and the change owed. Coins are taken from the smallest denomination up, as many
     * as the cost still needs, from lower slots first. When a cost is left over, one more coin of the smallest denomination
     * that has a coin left is taken, and the change is that coin's value less the rest. Changes nothing.
     *
     * @return empty when the coins in the main inventory are worth less than {@code cost}
     * @throws IllegalArgumentException when {@code cost} is not positive
     */
    public static Optional<Payment> plan(Inventory inventory, List<Denomination> currency, int cost) {
        Objects.requireNonNull(inventory, "inventory");
        if (cost <= 0) {
            throw new IllegalArgumentException("a payment costs at least 1 Aur, was " + cost);
        }
        int main = mainSize(inventory);
        Denomination[] coin = new Denomination[main];
        int[] held = new int[main];
        int[] taken = new int[main];
        List<Integer> slots = new ArrayList<>();
        long total = 0;
        for (int slot = 0; slot < main; slot++) {
            ItemStack stack = inventory.getItem(slot);
            Optional<Denomination> denomination = denominationOf(stack, currency);
            if (denomination.isPresent()) {
                coin[slot] = denomination.get();
                held[slot] = stack.getCount();
                total += (long) coin[slot].value() * held[slot];
                slots.add(slot);
            }
        }
        if (total < cost) {
            return Optional.empty();
        }

        long need = cost;
        for (Denomination denomination : currency.stream().sorted(Comparator.comparingInt(Denomination::value)).toList()) {
            long untaken = 0;
            for (int slot : slots) {
                if (coin[slot].equals(denomination)) {
                    untaken += held[slot] - taken[slot];
                }
            }
            long wanted = Math.min(untaken, need / denomination.value());
            for (int slot : slots) {
                if (wanted == 0) {
                    break;
                }
                if (coin[slot].equals(denomination)) {
                    int count = (int) Math.min(held[slot] - taken[slot], wanted);
                    taken[slot] += count;
                    wanted -= count;
                    need -= (long) count * denomination.value();
                }
            }
        }

        int change = 0;
        if (need > 0) {
            // The smallest coin still in the inventory, lowest slot first. Its value is above what is left, since every
            // smaller coin is taken already.
            int next = -1;
            for (int slot : slots) {
                if (taken[slot] < held[slot] && (next < 0 || coin[slot].value() < coin[next].value())) {
                    next = slot;
                }
            }
            taken[next]++;
            change = (int) (coin[next].value() - need);
        }

        List<Take> takes = new ArrayList<>();
        for (int slot = 0; slot < main; slot++) {
            if (taken[slot] > 0) {
                takes.add(new Take(slot, taken[slot]));
            }
        }
        return Optional.of(new Payment(takes, change));
    }

    /**
     * The coins that make {@code amount} Aur, largest denominations first. Each denomination gives as many coins as fit, in
     * stacks of the item's max stack size and at most 99.
     *
     * @return the stacks in order, empty for 0
     * @throws IllegalArgumentException when {@code amount} is negative, or when no denomination has value 1 and the amount
     *     cannot be made exactly
     */
    public static List<ItemStack> coins(List<Denomination> currency, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("an amount is not negative, was " + amount);
        }
        List<ItemStack> stacks = new ArrayList<>();
        long left = amount;
        for (Denomination denomination : currency.stream().sorted(Comparator.comparingInt(Denomination::value).reversed())
                .toList()) {
            long count = left / denomination.value();
            left -= count * denomination.value();
            int limit = Math.clamp(new ItemStack(denomination.item()).getMaxStackSize(), 1, MAX_COUNT);
            while (count > 0) {
                int piece = (int) Math.min(limit, count);
                stacks.add(new ItemStack(denomination.item(), piece));
                count -= piece;
            }
        }
        if (left != 0) {
            throw new IllegalArgumentException(amount + " Aur cannot be made from the currency: no denomination of value 1");
        }
        return List.copyOf(stacks);
    }

    private static Optional<Denomination> denominationOf(ItemStack stack, List<Denomination> currency) {
        if (stack.isEmpty() || !stack.getComponentsPatch().isEmpty()) {
            return Optional.empty();
        }
        return currency.stream().filter(denomination -> denomination.item() == stack.getItem()).findFirst();
    }

    private static int mainSize(Inventory inventory) {
        return inventory.getNonEquipmentItems().size();
    }
}
