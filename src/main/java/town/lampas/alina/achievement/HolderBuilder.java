package town.lampas.alina.achievement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.advancements.triggers.Criterion;
import net.minecraft.advancements.triggers.ImpossibleTrigger;
import net.minecraft.network.chat.Component;
import town.lampas.alina.content.AchievementsContent.Achievement;
import town.lampas.alina.content.AchievementsContent.Tab;
import town.lampas.alina.content.AchievementsContent.Tier;

/**
 * Builds the vanilla advancement holders and progress that {@link AdvancementSync} sends (ALINA-PLAN §9.4). A holder's
 * segments are criteria named {@code "0"}, {@code "1"}, ... that only Alina grants, so the requirements are one group
 * per segment. Server thread only.
 */
final class HolderBuilder {

    private HolderBuilder() {}

    /**
     * The holder of {@code slot}, with {@code segments} segments, its description gaining the earn count of a repeatable,
     * placed at ({@code x}, {@code y}) in vanilla's layout.
     */
    static AdvancementHolder holder(HolderLayout.Slot slot, int segments, int earnCount, float x, float y) {
        DisplayInfo display = switch (slot) {
            case HolderLayout.TabRoot root -> tabDisplay(root.tab());
            case HolderLayout.AchievementTier tier -> achievementDisplay(tier, earnCount);
        };
        display.setLocation(x, y);
        Criterion<?> impossible = new Criterion<>(CriteriaTriggers.IMPOSSIBLE, new ImpossibleTrigger.TriggerInstance());
        Map<String, Criterion<?>> criteria = new LinkedHashMap<>();
        for (int segment = 0; segment < segments; segment++) {
            criteria.put(name(segment), impossible);
        }
        Advancement advancement = new Advancement(slot.parent(), Optional.of(display), AdvancementRewards.EMPTY,
                criteria, requirements(segments), false, Optional.empty());
        return new AdvancementHolder(slot.id(), advancement);
    }

    /** The progress of a holder with {@code segments} segments, of which the first {@code doneSegments} are done. */
    static AdvancementProgress progress(int segments, int doneSegments) {
        AdvancementProgress progress = new AdvancementProgress();
        progress.update(requirements(segments));
        for (int segment = 0; segment < doneSegments; segment++) {
            progress.grantProgress(name(segment));
        }
        return progress;
    }

    /** The description a holder shows: the achievement's, with the earn count of a repeatable once it has been earned. */
    static Component description(Achievement achievement, int earnCount) {
        if (achievement.repeat().isEmpty()) {
            return achievement.description();
        }
        Component gained;
        if (achievement.tiers().isEmpty()) {
            if (earnCount < 1) {
                return achievement.description();
            }
            gained = AchievementText.earnedTimes(earnCount);
        } else {
            int completions = earnCount / achievement.tiers().size();
            if (completions < 1) {
                return achievement.description();
            }
            gained = AchievementText.completedTimes(completions);
        }
        return Component.empty().append(achievement.description()).append(Component.literal("\n")).append(gained);
    }

    private static AdvancementRequirements requirements(int segments) {
        List<List<String>> groups = new ArrayList<>(segments);
        for (int segment = 0; segment < segments; segment++) {
            groups.add(List.of(name(segment)));
        }
        return new AdvancementRequirements(groups);
    }

    private static String name(int segment) {
        return Integer.toString(segment);
    }

    private static DisplayInfo tabDisplay(Tab tab) {
        return new DisplayInfo(tab.icon(), tab.title(), Component.empty(), tab.background(), AdvancementType.TASK,
                false, false, false);
    }

    private static DisplayInfo achievementDisplay(HolderLayout.AchievementTier holder, int earnCount) {
        Achievement achievement = holder.achievement();
        Optional<Tier> tier = achievement.tiers().isEmpty()
                ? Optional.empty()
                : Optional.of(achievement.tiers().get(holder.tier() - 1));
        Component title = tier.flatMap(Tier::title).orElse(achievement.title());
        AdvancementType frame = tier.flatMap(Tier::frame).orElse(achievement.frame());
        // The toast is the content's, and the chat announcement is the service's, so the client never announces.
        return new DisplayInfo(achievement.icon(), title, description(achievement, earnCount), Optional.empty(), frame,
                achievement.toast(), false, false);
    }
}
