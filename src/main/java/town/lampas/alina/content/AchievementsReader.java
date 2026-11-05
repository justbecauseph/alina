package town.lampas.alina.content;

import com.google.gson.JsonElement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStackTemplate;
import town.lampas.alina.content.AchievementsContent.Achievement;
import town.lampas.alina.content.AchievementsContent.Tab;
import town.lampas.alina.content.AchievementsContent.Tier;
import town.lampas.haruhi.content.api.ExtensionContext;

/** Reads and validates the {@code alina:achievements} extension (ALINA-PLAN §6.2). */
final class AchievementsReader {
    static final Set<String> ACHIEVEMENTS_KEYS = Set.of("tabs", "achievements");
    static final Set<String> TAB_KEYS = Set.of("title", "icon", "background");
    static final Set<String> ACHIEVEMENT_KEYS = Set.of("tab", "parent", "title", "description", "icon", "frame",
            "hidden", "announce", "toast", "criteria", "requirements", "tiers", "repeat", "reward");
    static final Set<String> CRITERION_KEYS = Set.of("trigger", "conditions", "count");
    static final Set<String> TIER_KEYS = Set.of("count", "title", "frame", "reward");
    static final Set<String> REPEAT_KEYS = Set.of("cooldown");

    private AchievementsReader() {}

    static Optional<AchievementsContent> read(JsonElement value, ExtensionContext haruhiContext) {
        CountingContext context = new CountingContext(haruhiContext);
        String where = "extension " + context.id();
        Optional<Section> root = Section.of(value, where, context);
        if (root.isEmpty()) {
            return Optional.empty();
        }
        Section section = root.get();
        section.allowOnly(ACHIEVEMENTS_KEYS);

        Map<Identifier, Tab> tabs = new LinkedHashMap<>();
        Map<Identifier, String> claimedTabs = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : section.entries("tabs").entrySet()) {
            Identifier id = Json.keyId(entry.getKey());
            if (id == null) {
                section.add("tabs key '" + entry.getKey() + "' is not a valid id");
                continue;
            }
            if (GuildReader.claim(claimedTabs, id, entry.getKey(), "tabs", section)) {
                readTab(id, entry.getValue(), where + ", tab " + id, context).ifPresent(tab -> tabs.put(id, tab));
            }
        }

        Map<String, JsonElement> achievementEntries = section.entries("achievements");
        Set<Identifier> achievementIds = new HashSet<>();
        achievementEntries.keySet().stream().map(Json::keyId).filter(id -> id != null).forEach(achievementIds::add);

        Map<Identifier, Achievement> achievements = new LinkedHashMap<>();
        Map<Identifier, String> claimed = new HashMap<>();
        Map<Identifier, Identifier> declaredTabs = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : achievementEntries.entrySet()) {
            Identifier id = Json.keyId(entry.getKey());
            if (id == null) {
                section.add("achievements key '" + entry.getKey() + "' is not a valid id");
                continue;
            }
            if (GuildReader.claim(claimed, id, entry.getKey(), "achievements", section)) {
                readAchievement(id, entry.getValue(), where + ", achievement " + id, context, claimedTabs.keySet(),
                        achievementIds, declaredTabs)
                        .ifPresent(achievement -> achievements.put(id, achievement));
            }
        }
        checkParents(achievements, declaredTabs, where, context);
        checkAchievementReferences(achievements, achievementIds, where, context);
        return Optional.of(new AchievementsContent(tabs, achievements));
    }

    /**
     * An {@code alina:achievement_earned} criterion names an achievement of this extension. Commissions and volumes
     * named by other Alina triggers live in the {@code alina:guild} extension, which this parser cannot see; the Guild
     * reader checks the ones in commission proofs.
     */
    private static void checkAchievementReferences(
            Map<Identifier, Achievement> achievements, Set<Identifier> achievementIds, String where,
            ExtensionContext context) {
        for (Achievement achievement : achievements.values()) {
            achievement.criteria().forEach((name, spec) -> TriggerReferences.achievement(spec)
                    .filter(id -> !achievementIds.contains(id))
                    .ifPresent(id -> context.problem(where + ", achievement " + achievement.id() + ", criterion " + name,
                            "conditions name achievement '" + id + "', which is not an achievement in this extension")));
        }
    }

    private static Optional<Tab> readTab(Identifier id, JsonElement value, String where, ExtensionContext context) {
        Optional<Section> parsed = Section.of(value, where, context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section tab = parsed.get();
        tab.allowOnly(TAB_KEYS);
        Optional<Component> title = tab.required("title", Json.text(context.ops()));
        Optional<ItemStackTemplate> icon = tab.required("icon", Json.strict(ItemStackTemplate.CODEC, context.ops()));
        Optional<ClientAsset.ResourceTexture> background =
                tab.optional("background", Json.codec(ClientAsset.ResourceTexture.CODEC, context.ops()));
        // The texture path is textures/<path>.png, as in vanilla; a path written in full would be doubled.
        Optional<Identifier> backgroundId = background.map(ClientAsset.ResourceTexture::id);
        boolean backgroundForm = backgroundId
                .filter(texture -> texture.getPath().startsWith("textures/") || texture.getPath().endsWith(".png"))
                .isEmpty();
        if (!backgroundForm) {
            tab.add("background is written without textures/ and .png, as in vanilla: for "
                    + backgroundId.get() + " write " + backgroundId.get().withPath(path -> path
                            .replaceFirst("^textures/", "").replaceFirst("\\.png$", "")));
        }
        if (title.isEmpty() || icon.isEmpty() || (tab.has("background") && background.isEmpty()) || !backgroundForm) {
            return Optional.empty();
        }
        return Optional.of(new Tab(id, title.get(), icon.get(), background));
    }

    private static Optional<Achievement> readAchievement(
            Identifier id,
            JsonElement value,
            String where,
            ExtensionContext context,
            Set<Identifier> tabIds,
            Set<Identifier> achievementIds,
            Map<Identifier, Identifier> declaredTabs) {
        Optional<Section> parsed = Section.of(value, where, context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section achievement = parsed.get();
        achievement.allowOnly(ACHIEVEMENT_KEYS);
        int mark = problems(context);

        Optional<Identifier> tab = achievement.required("tab", Json::identifier);
        tab.filter(t -> !tabIds.contains(t))
                .ifPresent(t -> achievement.add("tab '" + t + "' is not a tab in this extension"));
        tab.ifPresent(t -> declaredTabs.put(id, t));
        Optional<Identifier> parent = achievement.optional("parent", Json::identifier);
        parent.filter(p -> !achievementIds.contains(p))
                .ifPresent(p -> achievement.add("parent '" + p + "' is not an achievement in this extension"));
        parent.filter(id::equals).ifPresent(p -> achievement.add("parent must not be the achievement itself"));
        Optional<Component> title = achievement.required("title", Json.text(context.ops()));
        Optional<Component> description = achievement.required("description", Json.text(context.ops()));
        Optional<ItemStackTemplate> icon =
                achievement.required("icon", Json.strict(ItemStackTemplate.CODEC, context.ops()));
        AdvancementType frame =
                achievement.optional("frame", Json.codec(AdvancementType.CODEC, context.ops())).orElse(AdvancementType.TASK);
        boolean hidden = achievement.optional("hidden", Json::bool).orElse(false);
        boolean announce = achievement.optional("announce", Json::bool).orElse(true);
        boolean toast = achievement.optional("toast", Json::bool).orElse(true);

        boolean tiered = achievement.has("tiers");
        Map<String, CriterionSpec> criteria = new LinkedHashMap<>();
        Map<String, JsonElement> criterionEntries = achievement.entries("criteria");
        if (criterionEntries.isEmpty() && (!achievement.has("criteria") || achievement.raw("criteria").isJsonObject())) {
            achievement.add("criteria must name at least one criterion");
        }
        if (tiered && criterionEntries.size() > 1) {
            achievement.add("tiers need exactly one criterion; this achievement has " + criterionEntries.size());
        }
        for (Map.Entry<String, JsonElement> entry : criterionEntries.entrySet()) {
            String name = entry.getKey();
            if (name.isEmpty()) {
                achievement.add("criteria names must not be empty");
                continue;
            }
            String criterionWhere = where + ", criterion " + name;
            Optional<Section> criterion = Section.of(entry.getValue(), criterionWhere, context);
            if (criterion.isEmpty()) {
                continue;
            }
            criterion.get().allowOnly(CRITERION_KEYS);
            boolean countClash = tiered && criterion.get().has("count");
            if (countClash) {
                criterion.get().add("count is set by the tiers of this achievement, not here");
            }
            // Read it even after a count clash, so the criterion's other problems are reported in the same pass.
            Optional<CriterionSpec> spec = CriterionReader.read(criterion.get(), tiered ? null : "count");
            if (!countClash) {
                spec.ifPresent(parsedSpec -> criteria.put(name, parsedSpec));
            }
        }

        List<List<String>> requirements = readRequirements(achievement, criterionEntries.keySet());
        List<Tier> tiers = tiered ? readTiers(achievement, where, context, criteria) : List.of();
        Optional<Duration> repeat = Optional.empty();
        if (achievement.has("repeat")) {
            Optional<Section> repeatSection = Section.of(achievement.raw("repeat"), where + ", repeat", context);
            if (repeatSection.isPresent()) {
                repeatSection.get().allowOnly(REPEAT_KEYS);
                repeat = repeatSection.get().required("cooldown", Json::duration);
            }
        }
        Optional<Reward> reward = achievement.has("reward")
                ? GuildReader.readReward(achievement.raw("reward"), where + ", reward", context)
                : Optional.of(Reward.NONE);

        if (problems(context) > mark || tab.isEmpty() || title.isEmpty() || description.isEmpty() || icon.isEmpty()
                || reward.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Achievement(id, tab.get(), parent, title.get(), description.get(), icon.get(), frame,
                hidden, announce, toast, criteria, requirements, tiers, repeat, reward.get()));
    }

    /**
     * Vanilla's AND of ORs over criterion names. Absent means every criterion is required. Every name must be a
     * criterion of the achievement, and every criterion must appear, as vanilla requires.
     */
    private static List<List<String>> readRequirements(Section achievement, Set<String> criterionNames) {
        if (!achievement.has("requirements")) {
            return criterionNames.stream().map(List::of).toList();
        }
        List<List<String>> groups = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        List<JsonElement> elements = achievement.elements("requirements");
        if (elements.isEmpty() && achievement.raw("requirements").isJsonArray()) {
            achievement.add("requirements must list at least one group");
        }
        for (int i = 0; i < elements.size(); i++) {
            JsonElement group = elements.get(i);
            if (!group.isJsonArray() || group.getAsJsonArray().isEmpty()) {
                achievement.add("requirements group " + (i + 1) + " must be a non-empty array of criterion names");
                continue;
            }
            List<String> names = new ArrayList<>();
            for (JsonElement name : group.getAsJsonArray()) {
                if (!Json.isString(name)) {
                    achievement.add("requirements group " + (i + 1) + " must list only criterion names");
                } else if (!criterionNames.contains(name.getAsString())) {
                    achievement.add("requirements group " + (i + 1) + " names '" + name.getAsString()
                            + "', which is not a criterion of this achievement");
                } else {
                    names.add(name.getAsString());
                    used.add(name.getAsString());
                }
            }
            groups.add(names);
        }
        for (String name : criterionNames) {
            if (!used.contains(name)) {
                achievement.add("criterion '" + name + "' is not in any requirements group");
            }
        }
        return groups;
    }

    private static List<Tier> readTiers(
            Section achievement, String where, ExtensionContext context, Map<String, CriterionSpec> criteria) {
        List<JsonElement> elements = achievement.elements("tiers");
        if (elements.isEmpty() && achievement.raw("tiers").isJsonArray()) {
            achievement.add("tiers must list at least one tier");
        }
        List<Tier> tiers = new ArrayList<>();
        int previous = 0;
        for (int i = 0; i < elements.size(); i++) {
            String tierWhere = where + ", tier " + (i + 1);
            Optional<Section> parsed = Section.of(elements.get(i), tierWhere, context);
            if (parsed.isEmpty()) {
                continue;
            }
            Section tier = parsed.get();
            tier.allowOnly(TIER_KEYS);
            Optional<Integer> count = tier.required("count", Json.integerAtLeast(1));
            count.filter(c -> c <= tiers.stream().mapToInt(Tier::count).max().orElse(0))
                    .ifPresent(c -> tier.add("count must be above the previous tier's count"));
            Optional<Component> title = tier.optional("title", Json.text(context.ops()));
            Optional<AdvancementType> frame = tier.optional("frame", Json.codec(AdvancementType.CODEC, context.ops()));
            Optional<Reward> reward = tier.has("reward")
                    ? GuildReader.readReward(tier.raw("reward"), tierWhere + ", reward", context)
                    : Optional.empty();
            if (count.isPresent() && count.get() > previous) {
                tiers.add(new Tier(count.get(), title, frame, reward));
                previous = count.get();
            }
        }
        int highest = tiers.stream().mapToInt(Tier::count).max().orElse(1);
        criteria.values().stream()
                .filter(spec -> highest > 1 && !CriterionReader.countable(spec.trigger()))
                .forEach(spec -> achievement.add("trigger " + spec.trigger()
                        + " fires repeatedly for one state, so it cannot be counted by tiers"));
        return tiers;
    }

    /**
     * Every parent is in the same tab as its child, no parent is hidden (a hidden achievement is withheld, so its child
     * would wait for a holder the client never gets), and following parents never loops.
     */
    private static void checkParents(
            Map<Identifier, Achievement> achievements,
            Map<Identifier, Identifier> declaredTabs,
            String where,
            ExtensionContext context) {
        for (Achievement achievement : achievements.values()) {
            achievement.parent().ifPresent(parent -> {
                Identifier parentTab = declaredTabs.get(parent);
                if (parentTab != null && !parentTab.equals(achievement.tab())) {
                    context.problem(where + ", achievement " + achievement.id(),
                            "parent '" + parent + "' is in tab " + parentTab + ", not " + achievement.tab());
                }
                Achievement parentAchievement = achievements.get(parent);
                if (parentAchievement != null && parentAchievement.hidden()) {
                    context.problem(where + ", achievement " + achievement.id(),
                            "parent '" + parent + "' is hidden, and a hidden achievement cannot be a parent");
                }
            });
            Set<Identifier> seen = new HashSet<>();
            Optional<Identifier> next = Optional.of(achievement.id());
            while (next.isPresent() && seen.add(next.get())) {
                Achievement current = achievements.get(next.get());
                next = current == null ? Optional.empty() : current.parent();
            }
            if (next.isPresent() && next.get().equals(achievement.id())) {
                context.problem(where + ", achievement " + achievement.id(), "parent chain loops back to itself");
            }
        }
    }

    /** A running count of problems: the extension context does not expose one, so it is wrapped. */
    private static int problems(ExtensionContext context) {
        return context instanceof CountingContext counting ? counting.count() : 0;
    }
}
