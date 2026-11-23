package town.lampas.alina.trade.pricing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import town.lampas.alina.content.TradeContent.Denomination;
import town.lampas.alina.content.TradeContent.Price;
import town.lampas.alina.content.TradeContent.SellRule;
import town.lampas.alina.content.TradeContent.Shop;

/**
 * What a shop pays for the stacks a player sells, and what "Sell all junk" sells now. A stack belongs to the first rule
 * of the shop's selling rules that matches it, as {@link Shop#ruleFor} decides.
 */
public final class SellPlanner {

    private SellPlanner() {}

    /**
     * What a shop pays for one stack: {@code aur} Aur for every {@code per} items.
     *
     * @param rule the rule the stack resolved to
     */
    public record Quote(SellRule rule, int aur, int per) {}

    /**
     * What "Sell all junk" sells: the items to take from the main inventory, how many, and the Aur paid.
     *
     * @param takes the takes, one per slot, in slot order
     * @param items the items sold
     * @param aur the Aur paid
     */
    public record JunkPlan(List<Take> takes, int items, int aur) {
        /** A plan that sells nothing. */
        public static final JunkPlan EMPTY = new JunkPlan(List.of(), 0, 0);

        public JunkPlan {
            takes = List.copyOf(takes);
        }
    }

    /**
     * What the shop pays for {@code stack}. Empty when the stack is empty, is a coin of the currency, holds other items
     * (a non-empty container or bundle), matches no rule, or matches a refuse rule.
     */
    public static Optional<Quote> quote(Shop shop, List<Denomination> currency, ItemStack stack) {
        Objects.requireNonNull(shop, "shop");
        if (stack.isEmpty() || Wallet.isCurrency(stack, currency) || holdsItems(stack)) {
            return Optional.empty();
        }
        Optional<SellRule> rule = shop.ruleFor(stack);
        if (rule.isEmpty() || rule.get().refuse() || rule.get().price().isEmpty()) {
            return Optional.empty();
        }
        Price price = rule.get().price().get();
        return Optional.of(new Quote(rule.get(), price.aur(), price.per()));
    }

    /**
     * What "Sell all junk" sells now. Each junk rule, in rule order, sells the main inventory stacks that belong to it, in
     * bundles of {@code per} items across the stacks, so a rule with {@code per} 4 sells 8 items from 3 and 5. The bundles
     * are cut so the Aur paid never goes over {@code budgetLeft}, counting the rules before it. The items are taken from
     * the lower slots first. Changes nothing.
     */
    public static JunkPlan junk(Shop shop, List<Denomination> currency, Inventory inventory, int budgetLeft) {
        Objects.requireNonNull(shop, "shop");
        Objects.requireNonNull(inventory, "inventory");
        if (shop.selling().isEmpty()) {
            return JunkPlan.EMPTY;
        }
        List<SellRule> rules = shop.selling().get().rules();
        int main = inventory.getNonEquipmentItems().size();
        // The quote of each main slot, or null when the shop does not buy it, and the index of the rule that owns it.
        Quote[] quotes = new Quote[main];
        int[] owner = new int[main];
        for (int slot = 0; slot < main; slot++) {
            ItemStack stack = inventory.getItem(slot);
            quotes[slot] = quote(shop, currency, stack).orElse(null);
            owner[slot] = firstMatch(rules, stack);
        }

        List<Take> takes = new ArrayList<>();
        long items = 0;
        long aur = 0;
        for (int index = 0; index < rules.size(); index++) {
            if (!rules.get(index).junk()) {
                continue;
            }
            List<Integer> slots = new ArrayList<>();
            long held = 0;
            for (int slot = 0; slot < main; slot++) {
                if (quotes[slot] != null && owner[slot] == index) {
                    slots.add(slot);
                    held += inventory.getItem(slot).getCount();
                }
            }
            if (slots.isEmpty()) {
                continue;
            }
            Quote quote = quotes[slots.get(0)];
            long bundles = held / quote.per();
            if (quote.aur() > 0) {
                long room = Math.max(0, (long) budgetLeft - aur);
                bundles = Math.min(bundles, room / quote.aur());
            }
            if (bundles == 0) {
                continue;
            }
            long toTake = bundles * quote.per();
            for (int slot : slots) {
                if (toTake == 0) {
                    break;
                }
                int count = (int) Math.min(inventory.getItem(slot).getCount(), toTake);
                takes.add(new Take(slot, count));
                toTake -= count;
            }
            items += bundles * quote.per();
            aur += bundles * quote.aur();
        }
        takes.sort(Comparator.comparingInt(Take::slot));
        return new JunkPlan(takes, clampToInt(items), clampToInt(aur));
    }

    /** The index of the first rule that matches the stack, the one {@link Shop#ruleFor} returns; -1 when none does. */
    private static int firstMatch(List<SellRule> rules, ItemStack stack) {
        for (int index = 0; index < rules.size(); index++) {
            if (rules.get(index).matches(stack)) {
                return index;
            }
        }
        return -1;
    }

    /** Whether the stack holds items: a container or bundle component with something in it. */
    private static boolean holdsItems(ItemStack stack) {
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null && container.nonEmptyItemCopyStream().findAny().isPresent()) {
            return true;
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        return bundle != null && !bundle.isEmpty();
    }

    private static int clampToInt(long value) {
        return Math.clamp(value, 0, Integer.MAX_VALUE);
    }
}
