package town.lampas.alina.trade.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Whether granted stacks fit the main inventory after the takes. Paper stacks to 64 and iron swords to 1, so a full inventory
 * of swords has no room for paper.
 */
class InventoryFitTest {

    @BeforeAll
    static void boot() {
        PricingFixtures.boot();
    }

    private static boolean fits(Inventory inventory, List<Take> removed, ItemStack... granted) {
        return InventoryFit.fits(inventory, removed, List.of(granted));
    }

    private static Inventory fullOfPaper() {
        Inventory inventory = PricingFixtures.inventory();
        for (int slot = 0; slot < 36; slot++) {
            inventory.setItem(slot, new ItemStack(Items.PAPER, 64));
        }
        return inventory;
    }

    private static Inventory fullOfSwords() {
        Inventory inventory = PricingFixtures.inventory();
        for (int slot = 0; slot < 36; slot++) {
            inventory.setItem(slot, new ItemStack(Items.IRON_SWORD));
        }
        return inventory;
    }

    @Test
    void anEmptyInventoryHoldsOneFullStackPerSlot() {
        Inventory inventory = PricingFixtures.inventory();
        ItemStack fullStack = new ItemStack(Items.PAPER, 64);

        assertTrue(InventoryFit.fits(inventory, List.of(), copies(fullStack, 36)));
        assertFalse(InventoryFit.fits(inventory, List.of(), copies(fullStack, 37)));
    }

    private static List<ItemStack> copies(ItemStack stack, int count) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            stacks.add(stack.copy());
        }
        return stacks;
    }

    @Test
    void aStackAboveItsMaxStackSizeIsCutAcrossSlots() {
        // 65 papers: 64 in one slot, 1 in the next.
        assertTrue(fits(PricingFixtures.inventory(), List.of(), new ItemStack(Items.PAPER, 65)));
    }

    @Test
    void aFullInventoryHasNoRoomForAnotherItem() {
        assertFalse(fits(fullOfSwords(), List.of(), new ItemStack(Items.PAPER, 1)));
    }

    @Test
    void aRemovalFreesTheSlotThatIsNeeded() {
        assertTrue(fits(fullOfSwords(), List.of(new Take(5, 1)), new ItemStack(Items.PAPER, 1)));
    }

    @Test
    void aStackFillsTheRoomLeftInAMatchingStackOnly() {
        // Slot 0 holds 60 papers, so 4 more fit there. Every other slot holds a sword, so nothing else fits.
        Inventory inventory = fullOfSwords();
        inventory.setItem(0, new ItemStack(Items.PAPER, 60));

        assertTrue(fits(inventory, List.of(), new ItemStack(Items.PAPER, 4)));
        assertFalse(fits(inventory, List.of(), new ItemStack(Items.PAPER, 5)));
    }

    @Test
    void aRemovalFromAMatchingStackMakesRoomInIt() {
        Inventory inventory = fullOfPaper();

        assertFalse(fits(inventory, List.of(), new ItemStack(Items.PAPER, 1)));
        assertTrue(fits(inventory, List.of(new Take(0, 10)), new ItemStack(Items.PAPER, 10)));
        assertFalse(fits(inventory, List.of(new Take(0, 10)), new ItemStack(Items.PAPER, 11)));
    }

    @Test
    void theInventoryIsLeftAsItWas() {
        Inventory inventory = fullOfPaper();
        inventory.setItem(7, new ItemStack(Items.PAPER, 3));

        InventoryFit.fits(inventory, List.of(new Take(7, 3)), List.of(new ItemStack(Items.PAPER, 64)));

        assertEquals(3, inventory.getItem(7).getCount());
        assertEquals(64, inventory.getItem(0).getCount());
    }

    @Test
    void aTakeOfMoreThanTheSlotHoldsIsRefused() {
        Inventory inventory = fullOfPaper();
        inventory.setItem(2, new ItemStack(Items.PAPER, 2));

        assertThrows(IllegalStateException.class,
                () -> InventoryFit.fits(inventory, List.of(new Take(2, 3)), List.of()));
    }
}
