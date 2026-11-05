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
 * parent before its children, and vanilla's layout over every holder.
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

    @Test
    void theLayoutOfAContentIsTheSameEachTime() {
        AchievementsContent content = sample();
        assertEquals(HolderLayout.of(content).position(AdvancementSync.holderId(id("visitor"), 1)),
                HolderLayout.of(content).position(AdvancementSync.holderId(id("visitor"), 1)));
    }

    private static Identifier id(String path) {
        return AchievementFixtures.id(path);
    }
}
