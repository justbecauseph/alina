package town.lampas.alina.trade.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The takes: their shape, and {@link Take#apply}, which checks every take before it removes one. */
class TakeTest {

    @BeforeAll
    static void boot() {
        PricingFixtures.boot();
    }

    private static Inventory papers(int slot0, int slot1) {
        Inventory inventory = PricingFixtures.inventory();
        inventory.setItem(0, new ItemStack(Items.PAPER, slot0));
        inventory.setItem(1, new ItemStack(Items.PAPER, slot1));
        return inventory;
    }

    @Test
    void aTakeNamesASlotAndRemovesAtLeastOne() {
        assertThrows(IllegalArgumentException.class, () -> new Take(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Take(-1, 1));
    }

    @Test
    void applyRemovesEachTakeAndReturnsWhatItRemoved() {
        Inventory inventory = papers(5, 3);

        List<ItemStack> removed = Take.apply(inventory, List.of(new Take(0, 2), new Take(1, 3)));

        assertEquals(List.of(2, 3), removed.stream().map(ItemStack::getCount).toList());
        assertEquals(3, inventory.getItem(0).getCount());
        assertEquals(0, inventory.getItem(1).getCount());
    }

    @Test
    void applyRemovesNothingWhenOneTakeCannotBeMet() {
        Inventory inventory = papers(5, 3);

        assertThrows(IllegalStateException.class, () -> Take.apply(inventory, List.of(new Take(0, 2), new Take(1, 4))));

        assertEquals(5, inventory.getItem(0).getCount());
        assertEquals(3, inventory.getItem(1).getCount());
    }

    @Test
    void takesOnOneSlotAreCheckedTogether() {
        Inventory inventory = papers(5, 3);

        assertThrows(IllegalStateException.class, () -> Take.apply(inventory, List.of(new Take(0, 3), new Take(0, 3))));

        assertEquals(5, inventory.getItem(0).getCount());
    }

    @Test
    void aTakeOutsideTheMainInventoryIsRefused() {
        Inventory inventory = papers(5, 3);

        assertThrows(IllegalStateException.class, () -> Take.apply(inventory, List.of(new Take(Inventory.SLOT_OFFHAND, 1))));
    }
}
