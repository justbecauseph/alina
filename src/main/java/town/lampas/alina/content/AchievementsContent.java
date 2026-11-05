package town.lampas.alina.content;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStackTemplate;

/** The parsed {@code alina:achievements} extension: tabs and achievements (ALINA-PLAN §6.2). Immutable. */
public record AchievementsContent(Map<Identifier, Tab> tabs, Map<Identifier, Achievement> achievements) {
    public static final AchievementsContent EMPTY = new AchievementsContent(Map.of(), Map.of());

    public AchievementsContent {
        tabs = Map.copyOf(tabs);
        achievements = Map.copyOf(achievements);
    }

    /** A tab of the vanilla advancement screen, shown as a root advancement. */
    public record Tab(
            Identifier id, Component title, ItemStackTemplate icon, Optional<ClientAsset.ResourceTexture> background) {}

    /**
     * One achievement.
     *
     * @param parent an achievement in the same tab, or empty for a child of the tab's root
     * @param criteria named criteria
     * @param requirements vanilla's AND of ORs over criterion names
     * @param tiers count thresholds on the one criterion, ascending; empty for an untiered achievement
     * @param repeat the cooldown after which the achievement can be earned again; empty when it is earned once
     */
    public record Achievement(
            Identifier id,
            Identifier tab,
            Optional<Identifier> parent,
            Component title,
            Component description,
            ItemStackTemplate icon,
            AdvancementType frame,
            boolean hidden,
            boolean announce,
            boolean toast,
            Map<String, CriterionSpec> criteria,
            List<List<String>> requirements,
            List<Tier> tiers,
            Optional<Duration> repeat,
            Reward reward) {

        public Achievement {
            criteria = Map.copyOf(criteria);
            requirements = requirements.stream().map(List::copyOf).toList();
            tiers = List.copyOf(tiers);
        }
    }

    /** A tier: a count threshold that may override the title, frame and reward. */
    public record Tier(int count, Optional<Component> title, Optional<AdvancementType> frame, Optional<Reward> reward) {}
}
