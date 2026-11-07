package town.lampas.alina.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static town.lampas.alina.achievement.AchievementFixtures.Ach;
import static town.lampas.alina.achievement.AchievementFixtures.HALL;
import static town.lampas.alina.achievement.AchievementFixtures.tab;

import java.util.Optional;
import java.util.Set;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.alina.content.AchievementsContent.Achievement;

/** What a holder shows and how it is named, against ALINA-PLAN §6.2 and §9.4 and the class javadoc of AdvancementSync. */
class HolderBuilderTest {

    @BeforeAll
    static void boot() {
        AchievementFixtures.boot();
    }

    @Test
    void aTierHoldsItsPlaceAndIsTheChildOfThePreviousTier() {
        Achievement bell = Ach.of("bell").criterion("rings", 1).tier(2).tier(5).build();
        Identifier first = AdvancementSync.holderId(bell.id(), 1);
        Identifier second = AdvancementSync.holderId(bell.id(), 2);
        AdvancementHolder holder = HolderBuilder.holder(
                new HolderLayout.AchievementTier(second, Optional.of(first), bell, 2), 5, 0, 3.5f, 1.25f);

        assertEquals(second, holder.id());
        assertEquals(Optional.of(first), holder.value().parent());
        assertEquals(3.5f, holder.value().display().orElseThrow().getX());
        assertEquals(1.25f, holder.value().display().orElseThrow().getY());
        assertEquals(Set.of("0", "1", "2", "3", "4"), holder.value().criteria().keySet());
        assertEquals(5, holder.value().requirements().requirements().size());
    }

    @Test
    void aTierTakesItsOwnTitleAndFrameWhenItGivesThem() {
        Achievement bell = Ach.of("bell").criterion("rings", 1).tier(2).tier(5, "Bell II", AdvancementType.CHALLENGE)
                .build();
        HolderLayout.AchievementTier first = new HolderLayout.AchievementTier(
                AdvancementSync.holderId(bell.id(), 1), Optional.of(AdvancementSync.tabHolderId(HALL)), bell, 1);
        HolderLayout.AchievementTier second = new HolderLayout.AchievementTier(
                AdvancementSync.holderId(bell.id(), 2), Optional.of(first.id()), bell, 2);

        assertEquals("bell title", HolderBuilder.holder(first, 2, 0, 0f, 0f).value().display().orElseThrow()
                .getTitle().getString());
        assertEquals(AdvancementType.TASK, HolderBuilder.holder(first, 2, 0, 0f, 0f).value().display().orElseThrow()
                .getType());
        assertEquals("Bell II", HolderBuilder.holder(second, 5, 0, 0f, 0f).value().display().orElseThrow()
                .getTitle().getString());
        assertEquals(AdvancementType.CHALLENGE, HolderBuilder.holder(second, 5, 0, 0f, 0f).value().display()
                .orElseThrow().getType());
    }

    @Test
    void aRepeatableGainsItsEarnCountOnceEarned() {
        Achievement hunt = Ach.of("hunt").criterion("kills", 1).repeating().build();
        assertEquals("Description.", HolderBuilder.description(hunt, 0).getString());
        assertEquals("Description.\nEarned 3 times", HolderBuilder.description(hunt, 3).getString());
    }

    @Test
    void aTieredRepeatableGainsCompletionsOfAllItsTiers() {
        Achievement bell = Ach.of("bell").criterion("rings", 1).tier(2).tier(5).repeating().build();
        assertEquals("Description.", HolderBuilder.description(bell, 1).getString());
        assertEquals("Description.\nCompleted 2 times", HolderBuilder.description(bell, 5).getString());
    }

    @Test
    void aNonRepeatableKeepsItsDescription() {
        Achievement once = Ach.of("once").criterion("c", 1).build();
        assertEquals("Description.", HolderBuilder.description(once, 4).getString());
    }

    @Test
    void aTabRootIsDoneWithoutAToast() {
        AdvancementHolder root = HolderBuilder.holder(new HolderLayout.TabRoot(
                AdvancementSync.tabHolderId(HALL), tab(HALL)), 1, 0, 0f, 0f);
        assertEquals(Optional.empty(), root.value().parent());
        assertEquals("hall", root.value().display().orElseThrow().getTitle().getString());
        assertFalse(root.value().display().orElseThrow().shouldShowToast());
        assertFalse(root.value().display().orElseThrow().shouldAnnounceChat());
        assertFalse(root.value().display().orElseThrow().isHidden());
        assertTrue(HolderBuilder.progress(1, 1).isDone());
    }

    @Test
    void progressCountsItsGrantedSegments() {
        AdvancementProgress partial = HolderBuilder.progress(3, 2);
        assertFalse(partial.isDone());
        assertEquals(2, count(partial.getCompletedCriteria()));
        assertEquals(1, count(partial.getRemainingCriteria()));
        assertTrue(HolderBuilder.progress(3, 3).isDone());
    }

    private static int count(Iterable<String> names) {
        int count = 0;
        for (String ignored : names) {
            count++;
        }
        return count;
    }
}
