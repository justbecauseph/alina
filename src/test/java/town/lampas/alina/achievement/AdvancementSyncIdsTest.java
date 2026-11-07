package town.lampas.alina.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** The holder ids of ALINA-PLAN §9.4 and the class javadoc of {@link AdvancementSync}. */
class AdvancementSyncIdsTest {

    @Test
    void aTabsRootIsNamedByItsTabId() {
        assertEquals("alina:tab/alina/guild", AdvancementSync.tabHolderId(Identifier.parse("alina:guild")).toString());
        assertEquals("alina:tab/minecraft/story/x",
                AdvancementSync.tabHolderId(Identifier.parse("minecraft:story/x")).toString());
    }

    @Test
    void anAchievementsFirstHolderIsNamedByItsId() {
        assertEquals("alina:achievement/alina/guild/first_commission",
                AdvancementSync.holderId(Identifier.parse("alina:guild/first_commission"), 1).toString());
    }

    @Test
    void laterTiersAddTheirTierNumberToTheAchievementsId() {
        Identifier achievement = Identifier.parse("alina:guild/zombie_slayer");
        assertEquals("alina:achievement/alina/guild/zombie_slayer/tier_2",
                AdvancementSync.holderId(achievement, 2).toString());
        assertEquals("alina:achievement/alina/guild/zombie_slayer/tier_37",
                AdvancementSync.holderId(achievement, 37).toString());
    }

    @Test
    void aTierBelowOneIsRefused() {
        Identifier achievement = Identifier.parse("alina:guild/zombie_slayer");
        assertThrows(IllegalArgumentException.class, () -> AdvancementSync.holderId(achievement, 0));
    }
}
