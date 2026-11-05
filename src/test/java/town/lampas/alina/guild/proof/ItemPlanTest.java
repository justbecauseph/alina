package town.lampas.alina.guild.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import town.lampas.alina.guild.proof.ItemPlan.Take;

/**
 * The plan's shape (ALINA-PLAN §8.3): at most one take per slot, in slot order, and each take removes at least one item.
 * Nothing here touches an item stack, so no Minecraft registry is needed.
 */
class ItemPlanTest {

    @Test
    void takesInSlotOrderAreKept() {
        ItemPlan plan = new ItemPlan(List.of(new Take(0, 1), new Take(7, 2), new Take(40, 3)));
        assertEquals(List.of(new Take(0, 1), new Take(7, 2), new Take(40, 3)), plan.takes());
    }

    @Test
    void anEmptyPlanIsAllowed() {
        assertEquals(List.of(), new ItemPlan(List.of()).takes());
    }

    @Test
    void twoTakesOnOneSlotAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ItemPlan(List.of(new Take(3, 1), new Take(3, 1))));
    }

    @Test
    void takesOutOfSlotOrderAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ItemPlan(List.of(new Take(4, 1), new Take(3, 1))));
    }

    @Test
    void aTakeOfNothingIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Take(3, 0));
    }
}
