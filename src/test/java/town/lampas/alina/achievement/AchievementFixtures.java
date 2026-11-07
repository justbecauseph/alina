package town.lampas.alina.achievement;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.triggers.ImpossibleTrigger;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import town.lampas.alina.TestBootstrap;
import town.lampas.alina.content.AchievementsContent;
import town.lampas.alina.content.AchievementsContent.Achievement;
import town.lampas.alina.content.AchievementsContent.Tab;
import town.lampas.alina.content.AchievementsContent.Tier;
import town.lampas.alina.content.CriterionSpec;
import town.lampas.alina.content.Reward;

/**
 * Builds {@code alina:achievements} content and standings for the achievement unit tests, without parsing JSON: the
 * records are made directly, with the names the tests need. Items need Minecraft bootstrapped, so {@link #boot} runs first.
 */
final class AchievementFixtures {
    static final Identifier HALL = id("hall");
    static final Identifier OTHER = id("other");

    private AchievementFixtures() {}

    static synchronized void boot() {
        TestBootstrap.boot();
    }

    /** An alina id, as the fixtures name achievements and tabs. */
    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("alina", path);
    }

    static Tab tab(Identifier id) {
        return new Tab(id, Component.literal(id.getPath()), new ItemStackTemplate(Items.WRITABLE_BOOK), Optional.empty());
    }

    static AchievementsContent content(List<Tab> tabs, List<Achievement> achievements) {
        Map<Identifier, Tab> tabMap = new LinkedHashMap<>();
        tabs.forEach(tab -> tabMap.put(tab.id(), tab));
        Map<Identifier, Achievement> achievementMap = new LinkedHashMap<>();
        achievements.forEach(achievement -> achievementMap.put(achievement.id(), achievement));
        return new AchievementsContent(tabMap, achievementMap);
    }

    /** A standing with {@code earnedTiers} earned in the current cycle, {@code earnCount} earns and the given counts. */
    static AchievementStanding standing(Identifier id, int earnedTiers, int earnCount, Map<String, Integer> counts) {
        return new AchievementStanding(id, earnedTiers, earnedTiers, earnCount, Optional.empty(), Optional.empty(),
                counts, false);
    }

    /** An achievement under construction. Its criteria are counted events of {@code alina:manual}. */
    static final class Ach {
        private final Identifier id;
        private Identifier tab = HALL;
        private Optional<Identifier> parent = Optional.empty();
        private boolean hidden;
        private Optional<Duration> repeat = Optional.empty();
        private final Map<String, CriterionSpec> criteria = new LinkedHashMap<>();
        private List<List<String>> requirements;
        private final List<Tier> tiers = new ArrayList<>();

        private Ach(Identifier id) {
            this.id = id;
        }

        static Ach of(String path) {
            return new Ach(id(path));
        }

        Ach in(Identifier tabId) {
            this.tab = tabId;
            return this;
        }

        Ach childOf(String parentPath) {
            this.parent = Optional.of(id(parentPath));
            return this;
        }

        Ach hidden() {
            this.hidden = true;
            return this;
        }

        Ach repeating() {
            this.repeat = Optional.of(Duration.ofDays(1));
            return this;
        }

        Ach criterion(String name, int count) {
            criteria.put(name, new CriterionSpec(id("manual"), new ImpossibleTrigger.TriggerInstance(), count));
            return this;
        }

        Ach requirements(List<List<String>> groups) {
            this.requirements = groups;
            return this;
        }

        Ach tier(int count) {
            tiers.add(new Tier(count, Optional.empty(), Optional.empty(), Optional.empty()));
            return this;
        }

        Ach tier(int count, String title, AdvancementType frame) {
            tiers.add(new Tier(count, Optional.of(Component.literal(title)), Optional.ofNullable(frame),
                    Optional.empty()));
            return this;
        }

        Achievement build() {
            List<List<String>> groups = requirements != null
                    ? requirements
                    : criteria.keySet().stream().map(List::of).toList();
            return new Achievement(id, tab, parent, Component.literal(id.getPath() + " title"),
                    Component.literal("Description."), new ItemStackTemplate(Items.PAPER), AdvancementType.TASK, hidden,
                    true, true, criteria, groups, tiers, repeat, Reward.NONE);
        }
    }
}
