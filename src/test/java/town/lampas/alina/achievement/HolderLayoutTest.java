package town.lampas.alina.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static town.lampas.alina.achievement.AchievementFixtures.Ach;
import static town.lampas.alina.achievement.AchievementFixtures.HALL;
import static town.lampas.alina.achievement.AchievementFixtures.OTHER;
import static town.lampas.alina.achievement.AchievementFixtures.content;
import static town.lampas.alina.achievement.AchievementFixtures.tab;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.alina.content.AchievementsContent;

/**
 * The holder order and the positions of {@link HolderLayout}: a tab's root first, then its achievements with every
 * parent before its children, and Alina's row rule over every holder, checked to exact values.
 */
class HolderLayoutTest {

    @BeforeAll
    static void boot() {
        AchievementFixtures.boot();
    }

    /** Two tabs. The hall has a hidden achievement, which the layout still places, and a tier chain under greeter. */
    private static AchievementsContent sample() {
        return content(
                List.of(tab(HALL), tab(OTHER)),
                List.of(
                        Ach.of("greeter").criterion("greet", 1).build(),
                        Ach.of("visitor").childOf("greeter").criterion("visit", 37).build(),
                        Ach.of("bell").childOf("greeter").criterion("rings", 1).tier(2).tier(5).build(),
                        Ach.of("hermit").hidden().criterion("hide", 1).build(),
                        Ach.of("far").in(OTHER).criterion("go", 1).build()));
    }

    @Test
    void everyHolderComesAfterItsParent() {
        HolderLayout layout = HolderLayout.of(sample());
        List<Identifier> ids = layout.slots().stream().map(HolderLayout.Slot::id).toList();
        for (HolderLayout.Slot slot : layout.slots()) {
            slot.parent().ifPresent(parent -> assertTrue(ids.indexOf(parent) >= 0 && ids.indexOf(parent) < ids.indexOf(slot.id()),
                    "parent " + parent + " of " + slot.id() + " is not before it"));
        }
        assertTrue(ids.indexOf(AdvancementSync.holderId(id("bell"), 1)) < ids.indexOf(AdvancementSync.holderId(id("bell"), 2)));
        assertTrue(ids.indexOf(AdvancementSync.holderId(id("greeter"), 1)) < ids.indexOf(AdvancementSync.holderId(id("visitor"), 1)));
        assertTrue(ids.indexOf(AdvancementSync.holderId(id("bell"), 1)) < ids.indexOf(AdvancementSync.holderId(id("visitor"), 1)),
                "siblings are in id order");
    }

    @Test
    void aTreeBuiltFromTheLayoutHasNoOrphans() {
        HolderLayout layout = HolderLayout.of(sample());
        List<AdvancementHolder> holders = new ArrayList<>();
        for (HolderLayout.Slot slot : layout.slots()) {
            HolderLayout.Position at = layout.position(slot.id());
            holders.add(HolderBuilder.holder(slot, 1, 0, at.x(), at.y()));
        }
        AdvancementTree tree = new AdvancementTree();
        tree.addAll(holders);
        for (AdvancementHolder holder : holders) {
            assertTrue(tree.get(holder.id()) != null, "orphaned: " + holder.id());
        }
        assertEquals(holders.size(), tree.nodes().size());
        int roots = 0;
        for (AdvancementNode ignored : tree.roots()) {
            roots++;
        }
        assertEquals(2, roots);
    }

    @Test
    void everyHolderHasItsPositionAndAChildLiesToTheRightOfItsParent() {
        HolderLayout layout = HolderLayout.of(sample());
        for (HolderLayout.Slot slot : layout.slots()) {
            HolderLayout.Position at = layout.position(slot.id());
            AdvancementHolder holder = HolderBuilder.holder(slot, 1, 0, at.x(), at.y());
            assertEquals(at.x(), holder.value().display().orElseThrow().getX());
            assertEquals(at.y(), holder.value().display().orElseThrow().getY());
            slot.parent().ifPresent(parent -> assertTrue(at.x() > layout.position(parent).x(),
                    slot.id() + " is not to the right of " + parent));
        }
    }

    /** The same content as {@link #sample()}, with its tabs and achievements inserted in the opposite order. */
    private static AchievementsContent sampleInReverseOrder() {
        return content(
                List.of(tab(OTHER), tab(HALL)),
                List.of(
                        Ach.of("far").in(OTHER).criterion("go", 1).build(),
                        Ach.of("hermit").hidden().criterion("hide", 1).build(),
                        Ach.of("bell").childOf("greeter").criterion("rings", 1).tier(2).tier(5).build(),
                        Ach.of("visitor").childOf("greeter").criterion("visit", 37).build(),
                        Ach.of("greeter").criterion("greet", 1).build()));
    }

    /**
     * The positions of {@link #sample()} by the rule of {@link HolderLayout}: a tab's root at the origin; the hall's
     * greeter has bell (tiers 1 and 2, the bell chain on row 0) and visitor (row 1) below it, as bell sorts before visitor;
     * the hidden hermit is a leaf on row 2, so it is placed though it is withheld; the other tab starts at row 0 again.
     */
    @Test
    void theRowsFollowTheRuleExactly() {
        assertExactPositions(HolderLayout.of(sample()));
    }

    /** Vanilla's walk of identity-hashed siblings made this differ between builds; Alina's rule does not depend on insertion. */
    @Test
    void theSameContentInAnotherInsertionOrderHasTheSameHoldersAndPositions() {
        HolderLayout first = HolderLayout.of(sample());
        HolderLayout second = HolderLayout.of(sampleInReverseOrder());
        assertEquals(ids(first), ids(second), "the holders in tree order");
        assertExactPositions(second);
        for (HolderLayout.Slot slot : first.slots()) {
            assertEquals(first.position(slot.id()), second.position(slot.id()), slot.id().toString());
        }
    }

    private static List<Identifier> ids(HolderLayout layout) {
        return layout.slots().stream().map(HolderLayout.Slot::id).toList();
    }

    private static void assertExactPositions(HolderLayout layout) {
        assertEquals(new HolderLayout.Position(0f, 0f), layout.position(AdvancementSync.tabHolderId(HALL)));
        assertEquals(new HolderLayout.Position(1f, 0f), layout.position(AdvancementSync.holderId(id("greeter"), 1)));
        assertEquals(new HolderLayout.Position(2f, 0f), layout.position(AdvancementSync.holderId(id("bell"), 1)));
        assertEquals(new HolderLayout.Position(3f, 0f), layout.position(AdvancementSync.holderId(id("bell"), 2)));
        assertEquals(new HolderLayout.Position(2f, 1f), layout.position(AdvancementSync.holderId(id("visitor"), 1)));
        assertEquals(new HolderLayout.Position(1f, 2f), layout.position(AdvancementSync.holderId(id("hermit"), 1)));
        assertEquals(new HolderLayout.Position(0f, 0f), layout.position(AdvancementSync.tabHolderId(OTHER)));
        assertEquals(new HolderLayout.Position(1f, 0f), layout.position(AdvancementSync.holderId(id("far"), 1)));
    }

    private static Identifier id(String path) {
        return AchievementFixtures.id(path);
    }
}
