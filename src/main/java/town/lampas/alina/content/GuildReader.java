package town.lampas.alina.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.advancements.predicates.MinMaxBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.Instrument;
import net.minecraft.world.level.Level;
import town.lampas.haruhi.content.api.ExtensionContext;

/** Reads and validates the {@code alina:guild} extension (ALINA-PLAN §6.1). */
final class GuildReader {
    static final Set<String> GUILD_KEYS = Set.of("settings", "commissions", "volumes");
    static final Set<String> SETTINGS_KEYS =
            Set.of("board_size", "active_cap", "reset", "default_cooldown", "salt", "board_graph", "board_reach");
    static final Set<String> RESET_KEYS = Set.of("time", "zone");
    static final Set<String> COMMISSION_KEYS = Set.of(
            "category", "kind", "name", "hint", "proof", "cooldown", "weight", "in_rotation", "reward");
    static final Set<String> REWARD_KEYS = Set.of("loot_table", "reputation");
    static final Set<String> VOLUME_KEYS = Set.of("dimension", "from", "to");
    static final Set<String> ITEM_PROOF_KEYS = Set.of("type", "all_of", "any_of");
    static final Set<String> ITEM_ENTRY_KEYS = Set.of("item", "checks", "count", "consume");
    static final Set<String> CRITERION_PROOF_KEYS = Set.of("type", "trigger", "conditions", "count");
    static final Set<String> DESCENT_PROOF_KEYS = Set.of("type", "start", "finish");
    static final Set<String> PROOF_TYPES = Set.of("item", "criterion", "descent");
    static final Set<String> BEES_CHECK_KEYS = Set.of("type", "count");
    static final Set<String> STEW_EFFECT_CHECK_KEYS = Set.of("type", "effect");
    static final Set<String> INSTRUMENT_TAG_CHECK_KEYS = Set.of("type", "tag");
    static final Set<String> CATEGORIES = Set.of("A", "B", "C", "D", "E", "F");
    static final String BOARD_RESET = "board_reset";

    private GuildReader() {}

    static Optional<GuildContent> read(JsonElement value, ExtensionContext context) {
        String where = "extension " + context.id();
        Optional<Section> root = Section.of(value, where, context);
        if (root.isEmpty()) {
            return Optional.empty();
        }
        Section guild = root.get();
        guild.allowOnly(GUILD_KEYS);

        Optional<GuildSettings> settings = guild.has("settings")
                ? readSettings(guild.raw("settings"), where + ", settings", context)
                : missing(guild, "settings is required");
        Map<Identifier, Volume> volumes = readVolumes(guild, where, context);
        Cooldown defaultCooldown = settings.map(GuildSettings::defaultCooldown).orElse(GuildSettings.DEFAULT_COOLDOWN);

        Map<Identifier, Commission> commissions = new LinkedHashMap<>();
        Map<Identifier, String> claimed = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : guild.entries("commissions").entrySet()) {
            Identifier id = Json.keyId(entry.getKey());
            if (id == null) {
                guild.add("commissions key '" + entry.getKey() + "' is not a valid id");
                continue;
            }
            if (!claim(claimed, id, entry.getKey(), "commissions", guild)) {
                continue;
            }
            readCommission(id, entry.getValue(), where + ", commission " + id, context, volumes, defaultCooldown)
                    .ifPresent(commission -> commissions.put(id, commission));
        }
        checkTriggerReferences(commissions, declared(guild.entries("volumes")), claimed.keySet(), where, context);
        return settings.map(parsed -> new GuildContent(parsed, commissions, volumes));
    }

    /** The ids of a section's keys that parse, including entries whose value failed to parse. */
    private static Set<Identifier> declared(Map<String, JsonElement> entries) {
        Set<Identifier> ids = new HashSet<>();
        entries.keySet().stream().map(Json::keyId).filter(Objects::nonNull).forEach(ids::add);
        return ids;
    }

    /**
     * A criterion proof on an Alina trigger names a volume or commission of this extension. A misspelt id would make a
     * proof that can never be met.
     */
    private static void checkTriggerReferences(
            Map<Identifier, Commission> commissions,
            Set<Identifier> volumeIds,
            Set<Identifier> commissionIds,
            String where,
            ExtensionContext context) {
        for (Commission commission : commissions.values()) {
            if (!(commission.proof() instanceof Proof.Criterion criterion)) {
                continue;
            }
            String proofWhere = where + ", commission " + commission.id() + ", proof";
            TriggerReferences.volume(criterion.criterion()).filter(id -> !volumeIds.contains(id)).ifPresent(id ->
                    context.problem(proofWhere, "conditions name volume '" + id + "', which is not a volume in this "
                            + "extension"));
            TriggerReferences.commission(criterion.criterion()).filter(id -> !commissionIds.contains(id)).ifPresent(id ->
                    context.problem(proofWhere, "conditions name commission '" + id + "', which is not a commission in "
                            + "this extension"));
        }
    }

    private static <T> Optional<T> missing(Section section, String message) {
        section.add(message);
        return Optional.empty();
    }

    /** Records that {@code key} names {@code id}; false, with a problem, when an earlier key already named it. */
    static boolean claim(Map<Identifier, String> claimed, Identifier id, String key, String what, Section section) {
        String earlier = claimed.putIfAbsent(id, key);
        if (earlier == null) {
            return true;
        }
        section.add(what + " keys '" + earlier + "' and '" + key + "' both name " + id);
        return false;
    }

    private static Optional<GuildSettings> readSettings(JsonElement value, String where, ExtensionContext context) {
        Optional<Section> parsed = Section.of(value, where, context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section settings = parsed.get();
        settings.allowOnly(SETTINGS_KEYS);
        int boardSize = settings.optional("board_size", Json.integerAtLeast(1)).orElse(GuildSettings.DEFAULT_BOARD_SIZE);
        int activeCap = settings.optional("active_cap", Json.integerAtLeast(1)).orElse(GuildSettings.DEFAULT_ACTIVE_CAP);
        LocalTime resetTime = GuildSettings.DEFAULT_RESET_TIME;
        ZoneId zone = GuildSettings.DEFAULT_ZONE;
        boolean resetOk = true;
        if (settings.has("reset")) {
            Optional<Section> reset = Section.of(settings.raw("reset"), where + ", reset", context);
            if (reset.isPresent()) {
                reset.get().allowOnly(RESET_KEYS);
                Optional<LocalTime> time = reset.get().optional("time", Json::timeOfDay);
                Optional<ZoneId> zoneId = reset.get().optional("zone", Json::zone);
                resetOk = (time.isPresent() || !reset.get().has("time")) && (zoneId.isPresent() || !reset.get().has("zone"));
                resetTime = time.orElse(resetTime);
                zone = zoneId.orElse(zone);
            } else {
                resetOk = false;
            }
        }
        Optional<Cooldown> defaultCooldown = settings.has("default_cooldown")
                ? settings.optional("default_cooldown", GuildReader::cooldown)
                : Optional.of(GuildSettings.DEFAULT_COOLDOWN);
        String salt = settings.optional("salt", Json::string).orElse("");
        Optional<Identifier> boardGraph = settings.required("board_graph", Json::identifier);
        boardGraph.filter(graph -> !context.graphIds().contains(graph))
                .ifPresent(graph -> settings.add("board_graph '" + graph + "' is not a dialogue in this bundle"));
        Optional<Double> reach = settings.optional("board_reach", Json::number);
        reach.filter(blocks -> blocks <= 0 || blocks > 64)
                .ifPresent(blocks -> settings.add("board_reach must be above 0 and at most 64"));

        if (!resetOk || defaultCooldown.isEmpty() || boardGraph.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new GuildSettings(boardSize, activeCap, resetTime, zone, defaultCooldown.get(), salt,
                boardGraph.get(), reach.orElse(GuildSettings.DEFAULT_BOARD_REACH)));
    }

    /** {@code "board_reset"} or a positive ISO-8601 duration. */
    static DataResult<Cooldown> cooldown(JsonElement value) {
        if (Json.isString(value) && value.getAsString().equals(BOARD_RESET)) {
            return DataResult.success(new Cooldown.UntilBoardReset());
        }
        return Json.duration(value).map(duration -> (Cooldown) new Cooldown.Fixed(duration))
                .mapError(message -> message + ", or \"" + BOARD_RESET + "\"");
    }

    private static Map<Identifier, Volume> readVolumes(Section guild, String where, ExtensionContext context) {
        Map<Identifier, Volume> volumes = new LinkedHashMap<>();
        Map<Identifier, String> claimed = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : guild.entries("volumes").entrySet()) {
            Identifier id = Json.keyId(entry.getKey());
            if (id == null) {
                guild.add("volumes key '" + entry.getKey() + "' is not a valid id");
                continue;
            }
            if (!claim(claimed, id, entry.getKey(), "volumes", guild)) {
                continue;
            }
            Optional<Section> parsed = Section.of(entry.getValue(), where + ", volume " + id, context);
            if (parsed.isEmpty()) {
                continue;
            }
            Section volume = parsed.get();
            volume.allowOnly(VOLUME_KEYS);
            Optional<Identifier> dimension = volume.required("dimension", Json::identifier);
            Optional<BlockPos> from = volume.required("from", GuildReader::blockPos);
            Optional<BlockPos> to = volume.required("to", GuildReader::blockPos);
            Optional<ResourceKey<Level>> level = dimension.map(d -> ResourceKey.create(Registries.DIMENSION, d));
            if (level.isPresent() && !ServerLookups.dimensionExists(level.get())) {
                volume.add("dimension " + dimension.get() + " is not a dimension on this server");
                continue;
            }
            if (level.isPresent() && from.isPresent() && to.isPresent()) {
                BlockPos a = from.get();
                BlockPos b = to.get();
                volumes.put(id, new Volume(id, level.get(),
                        new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                        new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()))));
            }
        }
        return volumes;
    }

    /** {@code [x, y, z]}, whole numbers. */
    static DataResult<BlockPos> blockPos(JsonElement value) {
        if (!value.isJsonArray() || value.getAsJsonArray().size() != 3) {
            return DataResult.error(() -> "must be an array of three whole numbers [x, y, z]");
        }
        JsonArray array = value.getAsJsonArray();
        int[] coordinates = new int[3];
        for (int i = 0; i < 3; i++) {
            Optional<Integer> coordinate = Json.integer(array.get(i)).result();
            if (coordinate.isEmpty()) {
                return DataResult.error(() -> "must be an array of three whole numbers [x, y, z]");
            }
            coordinates[i] = coordinate.get();
        }
        return DataResult.success(new BlockPos(coordinates[0], coordinates[1], coordinates[2]));
    }

    private static Optional<Commission> readCommission(
            Identifier id,
            JsonElement value,
            String where,
            ExtensionContext context,
            Map<Identifier, Volume> volumes,
            Cooldown defaultCooldown) {
        Optional<Section> parsed = Section.of(value, where, context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section commission = parsed.get();
        commission.allowOnly(COMMISSION_KEYS);
        Optional<String> category = commission.required("category", Json::string);
        category.filter(letter -> !CATEGORIES.contains(letter))
                .ifPresent(letter -> commission.add("category must be one of A, B, C, D, E or F"));
        Optional<Commission.Kind> kind = commission.required("kind", GuildReader::kind);
        Optional<Component> name = commission.required("name", Json.text(context.ops()));
        Optional<Component> hint = commission.required("hint", Json.text(context.ops()));
        Optional<Proof> proof = commission.has("proof")
                ? readProof(commission.raw("proof"), where + ", proof", context, volumes)
                : missing(commission, "proof is required");
        Optional<Cooldown> cooldown = commission.optional("cooldown", GuildReader::cooldown);
        boolean feat = kind.filter(k -> k == Commission.Kind.FEAT).isPresent();
        if (feat && commission.has("cooldown")) {
            commission.add("cooldown is not allowed on a feat, which is completed once");
        }
        int weight = commission.optional("weight", Json.integerAtLeast(1)).orElse(1);
        boolean inRotation = commission.optional("in_rotation", Json::bool).orElse(true);
        Optional<Reward> reward = commission.has("reward")
                ? readReward(commission.raw("reward"), where + ", reward", context)
                : Optional.of(Reward.NONE);

        boolean valid = category.filter(CATEGORIES::contains).isPresent() && kind.isPresent() && name.isPresent()
                && hint.isPresent() && proof.isPresent() && reward.isPresent()
                && (cooldown.isPresent() || !commission.has("cooldown")) && !(feat && commission.has("cooldown"));
        if (!valid) {
            return Optional.empty();
        }
        Optional<Cooldown> effective = feat ? Optional.empty() : Optional.of(cooldown.orElse(defaultCooldown));
        return Optional.of(new Commission(id, category.get().charAt(0), kind.get(), name.get(), hint.get(), proof.get(),
                effective, weight, inRotation, reward.get()));
    }

    private static DataResult<Commission.Kind> kind(JsonElement value) {
        return Json.string(value).flatMap(text -> switch (text) {
            case "repeatable" -> DataResult.success(Commission.Kind.REPEATABLE);
            case "feat" -> DataResult.success(Commission.Kind.FEAT);
            default -> DataResult.error(() -> "must be repeatable or feat");
        });
    }

    /** A reward object: {@code loot_table} (must exist on the running server) and {@code reputation}. */
    static Optional<Reward> readReward(JsonElement value, String where, ExtensionContext context) {
        Optional<Section> parsed = Section.of(value, where, context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section reward = parsed.get();
        reward.allowOnly(REWARD_KEYS);
        Optional<Identifier> lootTable = reward.optional("loot_table", Json::identifier);
        Optional<Integer> reputation = reward.optional("reputation", Json.integerAtLeast(0));
        if (lootTable.isPresent() && !ServerLookups.lootTableExists(lootTable.get())) {
            reward.add("loot_table " + lootTable.get() + " does not exist on this server");
            return Optional.empty();
        }
        if ((reward.has("loot_table") && lootTable.isEmpty()) || (reward.has("reputation") && reputation.isEmpty())) {
            return Optional.empty();
        }
        return Optional.of(new Reward(lootTable, reputation.orElse(0)));
    }

    private static Optional<Proof> readProof(
            JsonElement value, String where, ExtensionContext context, Map<Identifier, Volume> volumes) {
        Optional<Section> parsed = Section.of(value, where, context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section proof = parsed.get();
        Optional<String> type = proof.required("type", Json::string);
        return switch (type.orElse("")) {
            case "item" -> readItemProof(proof, where, context);
            case "criterion" -> {
                proof.allowOnly(CRITERION_PROOF_KEYS);
                yield CriterionReader.read(proof, "count").map(Proof.Criterion::new);
            }
            case "descent" -> readDescentProof(proof, volumes);
            default -> {
                // Without a known type, still report keys no proof type has.
                proof.allowOnly(union(ITEM_PROOF_KEYS, CRITERION_PROOF_KEYS, DESCENT_PROOF_KEYS));
                if (type.isPresent()) {
                    proof.add("type must be item, criterion or descent");
                }
                yield Optional.empty();
            }
        };
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> all = new HashSet<>();
        for (Set<String> set : sets) {
            all.addAll(set);
        }
        return all;
    }

    private static Optional<Proof> readDescentProof(Section proof, Map<Identifier, Volume> volumes) {
        proof.allowOnly(DESCENT_PROOF_KEYS);
        Optional<Identifier> start = proof.required("start", Json::identifier);
        Optional<Identifier> finish = proof.required("finish", Json::identifier);
        start.filter(id -> !volumes.containsKey(id))
                .ifPresent(id -> proof.add("start '" + id + "' is not a volume in this extension"));
        finish.filter(id -> !volumes.containsKey(id))
                .ifPresent(id -> proof.add("finish '" + id + "' is not a volume in this extension"));
        if (start.isEmpty() || finish.isEmpty() || !volumes.containsKey(start.get()) || !volumes.containsKey(finish.get())) {
            return Optional.empty();
        }
        if (start.get().equals(finish.get())) {
            proof.add("start and finish must be different volumes");
            return Optional.empty();
        }
        return Optional.of(new Proof.Descent(start.get(), finish.get()));
    }

    private static Optional<Proof> readItemProof(Section proof, String where, ExtensionContext context) {
        proof.allowOnly(ITEM_PROOF_KEYS);
        boolean allOf = proof.has("all_of");
        boolean exactlyOne = allOf != proof.has("any_of");
        if (!exactlyOne) {
            proof.add("needs exactly one of all_of and any_of");
        }
        boolean valid = exactlyOne;
        List<Proof.Entry> entries = new ArrayList<>();
        // Read every list that is present, so its entries' problems are reported too.
        for (String key : List.of("all_of", "any_of")) {
            if (!proof.has(key)) {
                continue;
            }
            List<JsonElement> elements = proof.elements(key);
            if (elements.isEmpty()) {
                if (proof.raw(key).isJsonArray()) {
                    proof.add(key + " must list at least one item");
                }
                valid = false;
            }
            for (int i = 0; i < elements.size(); i++) {
                Optional<Proof.Entry> entry =
                        readItemEntry(elements.get(i), where + ", " + key + " entry " + (i + 1), context);
                entry.ifPresent(entries::add);
                valid &= entry.isPresent();
            }
        }
        return valid ? Optional.of(new Proof.Items(allOf, entries)) : Optional.empty();
    }

    private static Optional<Proof.Entry> readItemEntry(JsonElement value, String where, ExtensionContext context) {
        Optional<Section> parsed = Section.of(value, where, context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section entry = parsed.get();
        entry.allowOnly(ITEM_ENTRY_KEYS);
        boolean countInItem = entry.has("item") && entry.raw("item").isJsonObject()
                && entry.raw("item").getAsJsonObject().has("count");
        Optional<ItemPredicate> item;
        if (countInItem) {
            entry.add("item must not have a count; give the entry's count instead");
            item = Optional.empty();
        } else {
            item = entry.required("item", Json.strict(ItemPredicate.CODEC, context.ops()));
        }
        Optional<Integer> count = entry.has("count")
                ? entry.optional("count", Json.integerAtLeast(1))
                : Optional.of(1);
        Optional<Boolean> consume = entry.has("consume") ? entry.optional("consume", Json::bool) : Optional.of(true);
        List<JsonElement> checkElements = entry.elements("checks");
        List<ItemCheck> checks = new ArrayList<>();
        boolean valid = item.isPresent() && count.isPresent() && consume.isPresent()
                && (!entry.has("checks") || entry.raw("checks").isJsonArray());
        for (int i = 0; i < checkElements.size(); i++) {
            Optional<ItemCheck> check = readCheck(checkElements.get(i), where + ", check " + (i + 1), context);
            check.ifPresent(checks::add);
            valid &= check.isPresent();
        }
        return valid ? Optional.of(new Proof.Entry(item.get(), checks, count.get(), consume.get())) : Optional.empty();
    }

    private static Optional<ItemCheck> readCheck(JsonElement value, String where, ExtensionContext context) {
        Optional<Section> parsed = Section.of(value, where, context);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Section check = parsed.get();
        Optional<Identifier> type = check.required("type", Json::identifier);
        if (type.isEmpty()) {
            check.allowOnly(union(BEES_CHECK_KEYS, STEW_EFFECT_CHECK_KEYS, INSTRUMENT_TAG_CHECK_KEYS));
            return Optional.empty();
        }
        if (type.get().equals(ItemCheck.BEES)) {
            check.allowOnly(BEES_CHECK_KEYS);
            return check.required("count", Json.strict(MinMaxBounds.Ints.CODEC, context.ops()))
                    .map(ItemCheck.BeeCount::new);
        }
        if (type.get().equals(ItemCheck.STEW_EFFECT)) {
            check.allowOnly(STEW_EFFECT_CHECK_KEYS);
            Optional<Identifier> effect = check.required("effect", Json::identifier);
            if (effect.isEmpty()) {
                return Optional.empty();
            }
            Optional<Holder.Reference<MobEffect>> holder = BuiltInRegistries.MOB_EFFECT.get(effect.get());
            if (holder.isEmpty()) {
                check.add("effect " + effect.get() + " is not a registered effect");
                return Optional.empty();
            }
            return Optional.of(new ItemCheck.StewEffect(holder.get()));
        }
        if (type.get().equals(ItemCheck.INSTRUMENT_TAG)) {
            check.allowOnly(INSTRUMENT_TAG_CHECK_KEYS);
            Optional<Identifier> tag = check.required("tag", Json::identifier);
            if (tag.isEmpty()) {
                return Optional.empty();
            }
            TagKey<Instrument> key = TagKey.create(Registries.INSTRUMENT, tag.get());
            if (!ServerLookups.tagExists(key)) {
                check.add("tag #" + tag.get() + " is not an instrument tag on this server");
                return Optional.empty();
            }
            return Optional.of(new ItemCheck.InstrumentTag(key));
        }
        check.allowOnly(union(BEES_CHECK_KEYS, STEW_EFFECT_CHECK_KEYS, INSTRUMENT_TAG_CHECK_KEYS));
        check.add("type " + type.get() + " is not an item check; use alina:bees, alina:stew_effect or alina:instrument_tag");
        return Optional.empty();
    }
}
