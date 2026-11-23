package town.lampas.alina.trade.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static town.lampas.alina.trade.pricing.PricingFixtures.currency;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.alina.content.TradeContent.Denomination;
import town.lampas.alina.trade.pricing.Wallet.Payment;

/**
 * The currency in the main inventory. Coins are nuggets (value 1) and ingots (value 9), as the content's examples have
 * them. Expected values are worked out by hand from the rules in the {@link Wallet} javadoc.
 */
class WalletTest {

    @BeforeAll
    static void boot() {
        PricingFixtures.boot();
    }

    private static List<Item> items(List<ItemStack> stacks) {
        return stacks.stream().map(ItemStack::getItem).toList();
    }

    private static List<Integer> counts(List<ItemStack> stacks) {
        return stacks.stream().map(ItemStack::getCount).toList();
    }

    @Test
    void theTotalCountsTheUnmodifiedCoinsOfTheMainInventory() {
        Inventory inventory = PricingFixtures.inventory();
        inventory.setItem(0, new ItemStack(Items.GOLD_INGOT, 2));
        inventory.setItem(5, new ItemStack(Items.GOLD_NUGGET, 3));
        inventory.setItem(35, new ItemStack(Items.GOLD_NUGGET, 1));
        ItemStack renamed = new ItemStack(Items.GOLD_NUGGET, 4);
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Lucky"));
        inventory.setItem(9, renamed);
        inventory.setItem(10, new ItemStack(Items.PAPER, 5));

        // 2 ingots (18), 3 nuggets (3) and 1 nugget (1). The renamed nuggets and the paper are not money.
        assertEquals(22, Wallet.total(inventory, currency()));
    }

    @Test
    void aPaymentThatIsExactlyTheCoinsHeldTakesThemAllWithNoChange() {
        Inventory inventory = PricingFixtures.inventory();
        inventory.setItem(0, new ItemStack(Items.GOLD_NUGGET, 2));
        inventory.setItem(1, new ItemStack(Items.GOLD_INGOT, 1));

        Payment payment = Wallet.plan(inventory, currency(), 11).orElseThrow();

        assertEquals(List.of(new Take(0, 2), new Take(1, 1)), payment.takes());
        assertEquals(0, payment.change());
    }

    @Test
    void aPaymentNeedingChangeTakesTheSmallCoinsFirstAndOneMoreCoin() {
        // Both nuggets (need 11 left), one ingot (need 2 left), then one more ingot: 20 paid for 13, so the change is 7.
        Inventory inventory = PricingFixtures.inventory();
        inventory.setItem(0, new ItemStack(Items.GOLD_NUGGET, 2));
        inventory.setItem(1, new ItemStack(Items.GOLD_INGOT, 3));

        Payment payment = Wallet.plan(inventory, currency(), 13).orElseThrow();

        assertEquals(List.of(new Take(0, 2), new Take(1, 2)), payment.takes());
        assertEquals(7, payment.change());
    }

    @Test
    void aPaymentWithOnlyIngotsGivesChangeFromTheLastIngot() {
        // One ingot (need 4 left), then one more ingot: 18 paid for 13, so the change is 5.
        Inventory inventory = PricingFixtures.inventory();
        inventory.setItem(0, new ItemStack(Items.GOLD_INGOT, 2));

        Payment payment = Wallet.plan(inventory, currency(), 13).orElseThrow();

        assertEquals(List.of(new Take(0, 2)), payment.takes());
        assertEquals(5, payment.change());
    }

    @Test
    void severalCoinsFromOneSlotAreOneTake() {
        // One ingot, then one more from the same slot: two ingots (18) for 10, so the change is 8.
        Inventory inventory = PricingFixtures.inventory();
        inventory.setItem(0, new ItemStack(Items.GOLD_INGOT, 5));

        Payment payment = Wallet.plan(inventory, currency(), 10).orElseThrow();

        assertEquals(List.of(new Take(0, 2)), payment.takes());
        assertEquals(8, payment.change());
    }

    @Test
    void coinsAreTakenFromLowerSlotsFirst() {
        // Three nuggets are needed: two from slot 1 (the lower slot), then one from slot 3.
        Inventory inventory = PricingFixtures.inventory();
        inventory.setItem(3, new ItemStack(Items.GOLD_NUGGET, 2));
        inventory.setItem(1, new ItemStack(Items.GOLD_NUGGET, 2));

        Payment payment = Wallet.plan(inventory, currency(), 3).orElseThrow();

        assertEquals(List.of(new Take(1, 2), new Take(3, 1)), payment.takes());
        assertEquals(0, payment.change());
    }

    @Test
    void aPaymentIsRefusedWhenTheCoinsAreWorthLess() {
        Inventory inventory = PricingFixtures.inventory();
        inventory.setItem(0, new ItemStack(Items.GOLD_NUGGET, 2));
        inventory.setItem(1, new ItemStack(Items.GOLD_INGOT, 1));

        Optional<Payment> payment = Wallet.plan(inventory, currency(), 12);

        assertTrue(payment.isEmpty());
    }

    @Test
    void aPaymentOfNothingIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Wallet.plan(PricingFixtures.inventory(), currency(), 0));
    }

    @Test
    void aPaymentIgnoresRenamedCoins() {
        Inventory inventory = PricingFixtures.inventory();
        ItemStack renamed = new ItemStack(Items.GOLD_INGOT, 2);
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Lucky"));
        inventory.setItem(0, renamed);

        assertTrue(Wallet.plan(inventory, currency(), 1).isEmpty());
        assertFalse(Wallet.isCurrency(renamed, currency()));
    }

    @Test
    void coinsForNothingIsNoStacks() {
        assertEquals(List.of(), Wallet.coins(currency(), 0));
    }

    @Test
    void fortyAurIsFourIngotsAndFourNuggets() {
        List<ItemStack> coins = Wallet.coins(currency(), 40);

        assertEquals(List.of(Items.GOLD_INGOT, Items.GOLD_NUGGET), items(coins));
        assertEquals(List.of(4, 4), counts(coins));
    }

    @Test
    void aLargeAmountIsCutIntoStacksOfTheMaxStackSize() {
        // 1803 Aur: 200 ingots, then 3 nuggets. Gold ingots and nuggets stack to 64, so the ingots are cut 64, 64, 64, 8.
        List<ItemStack> coins = Wallet.coins(currency(), 1803);

        assertEquals(List.of(Items.GOLD_INGOT, Items.GOLD_INGOT, Items.GOLD_INGOT, Items.GOLD_INGOT, Items.GOLD_NUGGET),
                items(coins));
        assertEquals(List.of(64, 64, 64, 8, 3), counts(coins));
    }

    @Test
    void withoutAValueOneDenominationAnAmountTheDenominationsMissIsRefused() {
        List<Denomination> ingotsOnly = List.of(PricingFixtures.ingot());

        assertEquals(List.of(Items.GOLD_INGOT), items(Wallet.coins(ingotsOnly, 9)));
        assertThrows(IllegalArgumentException.class, () -> Wallet.coins(ingotsOnly, 10));
    }

    @Test
    void aNegativeAmountIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Wallet.coins(currency(), -1));
    }
}
