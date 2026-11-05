package town.lampas.alina.achievement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.TreeNodePosition;
import net.minecraft.resources.Identifier;
import town.lampas.alina.Alina;
import town.lampas.alina.content.AchievementsContent;
import town.lampas.alina.content.AchievementsContent.Achievement;
import town.lampas.alina.content.AchievementsContent.Tab;

/**
 * The advancement holders of one content, in tree order, and where vanilla's layout puts each one (ALINA-PLAN §9.4).
 * Each tab is a root. Its top-level achievements are its children, in id order, and an achievement's tiers are a chain,
 * each the child of the previous; the first tier is the child of its parent achievement's first tier, or of the tab's
 * root. The list has every parent before its children. Positions come from vanilla's {@link TreeNodePosition}, run over
 * every holder of the content, hidden ones included, so withholding a holder from one player leaves a gap and moves
 * nothing else. Built once per content. Server thread only.
 */
final class HolderLayout {

    /** One holder of the content. */
    sealed interface Slot permits TabRoot, AchievementTier {
        Identifier id();

        /** The holder this one is the child of; empty for a tab's root. */
        Optional<Identifier> parent();
    }

    /** A tab's root holder. */
    record TabRoot(Identifier id, Tab tab) implements Slot {
        @Override
        public Optional<Identifier> parent() {
            return Optional.empty();
        }
    }

    /** One tier of an achievement, 1-based. An achievement without tiers has tier 1 only. */
    record AchievementTier(Identifier id, Optional<Identifier> parent, Achievement achievement, int tier)
            implements Slot {}

    /** Where a holder is in the vanilla screen: vanilla's layout units, x for the column and y for the row. */
    record Position(float x, float y) {}

    private final List<Slot> slots;
    private final Map<Identifier, List<AchievementTier>> tiers;
    private final Map<Identifier, Position> positions;

    private HolderLayout(List<Slot> slots, Map<Identifier, List<AchievementTier>> tiers,
            Map<Identifier, Position> positions) {
        this.slots = slots;
        this.tiers = tiers;
        this.positions = positions;
    }

    /** Lays out every holder of {@code content}. */
    static HolderLayout of(AchievementsContent content) {
        List<Achievement> achievements = content.achievements().values().stream()
                .sorted(Comparator.comparing(Achievement::id))
                .toList();
        Map<Identifier, List<Achievement>> children = new HashMap<>();
        for (Achievement achievement : achievements) {
            achievement.parent().ifPresent(parent ->
                    children.computeIfAbsent(parent, key -> new ArrayList<>()).add(achievement));
        }
        List<Slot> slots = new ArrayList<>();
        Set<Identifier> used = new HashSet<>();
        List<Tab> tabs = content.tabs().values().stream().sorted(Comparator.comparing(Tab::id)).toList();
        for (Tab tab : tabs) {
            Identifier root = AdvancementSync.tabHolderId(tab.id());
            if (used.add(root)) {
                slots.add(new TabRoot(root, tab));
            }
            for (Achievement achievement : achievements) {
                if (achievement.parent().isEmpty() && achievement.tab().equals(tab.id())) {
                    emit(achievement, root, children, slots, used);
                }
            }
        }

        AdvancementTree tree = new AdvancementTree();
        // Layout needs the structure and a display, not the segments or the text, so every holder is built with one segment.
        tree.addAll(slots.stream().map(slot -> HolderBuilder.holder(slot, 1, 0, 0f, 0f)).toList());
        for (AdvancementNode root : tree.roots()) {
            TreeNodePosition.run(root);
        }
        Map<Identifier, Position> positions = new HashMap<>();
        for (Slot slot : slots) {
            AdvancementNode node = tree.get(slot.id());
            if (node != null) {
                node.advancement().display().ifPresent(display ->
                        positions.put(slot.id(), new Position(display.getX(), display.getY())));
            }
        }

        Map<Identifier, List<AchievementTier>> tiers = new HashMap<>();
        for (Slot slot : slots) {
            if (slot instanceof AchievementTier tier) {
                tiers.computeIfAbsent(tier.achievement().id(), key -> new ArrayList<>()).add(tier);
            }
        }
        return new HolderLayout(List.copyOf(slots), Map.copyOf(tiers), Map.copyOf(positions));
    }

    /** Every holder, parents before children. */
    List<Slot> slots() {
        return slots;
    }

    /** The tiers of an achievement in order, or an empty list for an unknown id. */
    List<AchievementTier> tiersOf(Identifier achievement) {
        return tiers.getOrDefault(achievement, List.of());
    }

    /** The holder's position, or the origin for an id the content does not have. */
    Position position(Identifier holder) {
        return positions.getOrDefault(holder, new Position(0f, 0f));
    }

    /**
     * Adds an achievement's tiers, then its child achievements, depth first. A holder id already used, by another
     * achievement or by a tab, stops the achievement: it is logged and not shown.
     */
    private static void emit(Achievement achievement, Identifier parentHolder,
            Map<Identifier, List<Achievement>> children, List<Slot> slots, Set<Identifier> used) {
        int count = Math.max(1, achievement.tiers().size());
        List<Identifier> ids = new ArrayList<>();
        for (int tier = 1; tier <= count; tier++) {
            ids.add(AdvancementSync.holderId(achievement.id(), tier));
        }
        if (ids.stream().anyMatch(used::contains)) {
            Alina.LOGGER.warn("Alina achievement {} has a holder id that another holder uses; it is not shown",
                    achievement.id());
            return;
        }
        Identifier previous = parentHolder;
        for (int tier = 1; tier <= count; tier++) {
            Identifier id = ids.get(tier - 1);
            used.add(id);
            slots.add(new AchievementTier(id, Optional.of(previous), achievement, tier));
            previous = id;
        }
        for (Achievement child : children.getOrDefault(achievement.id(), List.of())) {
            emit(child, AdvancementSync.holderId(achievement.id(), 1), children, slots, used);
        }
    }
}
