package town.lampas.alina.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import town.lampas.alina.Alina;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.content.CriterionSpec;
import town.lampas.alina.content.GuildContent;
import town.lampas.alina.content.Proof;
import town.lampas.alina.criteria.CriterionWatchers;
import town.lampas.alina.guild.proof.ItemProofPlanner;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;

/**
 * Spikes for the Lampas content (ALINA-PLAN §12 S5, §14 D3 and F2), run on the pinned releases.
 *
 * <p>S5 (F3) and D3 need mods that the Lampas pack ships and that build.gradle loads into the dev runtime: Gateways to
 * Eternity 7.1.0 and Farmer's Delight 3.6.30. Each checks {@code FabricLoader.isModLoaded} first and, without its mod, logs
 * that it skipped and succeeds. Neither mod is a compile dependency, so nothing here names one of its classes: gateways
 * and dishes are found by id, and the Gateways trigger is called by reflection. F2 reads the server's own chest loot
 * tables and needs no mod.
 */
@SuppressWarnings("removal")
public final class SpikeGameTests {
    private static final String GATEWAYS = "gateways";
    private static final String FARMERS_DELIGHT = "farmersdelight";
    private static final String GATEWAY_REGISTRY = "dev.shadowsoffire.gateways.gate.GatewayRegistry";
    private static final String GATEWAY = "dev.shadowsoffire.gateways.gate.Gateway";
    private static final Identifier FINISH_GATEWAY = Identifier.parse("gateways:finish_gateway");
    private static final Identifier OVERWORLDIAN_NIGHTS = Identifier.parse("gateways:overworldian_nights");
    private static final Identifier EMERALD_GROVE = Identifier.parse("gateways:emerald_grove");

    /** The nine gateways of Gateways to Eternity 7.1.0 ({@code data/gateways/gateways/gateways/**}, ids gateways:path). */
    private static final List<String> GATEWAY_IDS = List.of(
            "gateways:basic/blaze", "gateways:basic/enderman", "gateways:basic/slime", "gateways:emerald_grove",
            "gateways:endless/automaton_fabricator", "gateways:endless/blaze", "gateways:hellish_fortress",
            "gateways:iron_automaton", "gateways:overworldian_nights");

    /**
     * The discs of minecraft:creeper_drop_music_discs that no chest loot table drops, the 26.2 data of the chest tables
     * under data/minecraft/loot_table/chests/** followed through their references. Chests drop only music_disc_13 and
     * music_disc_cat from that tag. The test below derives this list again from the server's data.
     */
    private static final List<String> F2_DISCS = List.of(
            "minecraft:music_disc_11", "minecraft:music_disc_blocks", "minecraft:music_disc_chirp",
            "minecraft:music_disc_far", "minecraft:music_disc_mall", "minecraft:music_disc_mellohi",
            "minecraft:music_disc_stal", "minecraft:music_disc_strad", "minecraft:music_disc_wait",
            "minecraft:music_disc_ward");

    /** Runs {@code body}; a failure that is not a GameTest assertion fails the test instead of stopping the server. */
    private static void check(GameTestHelper helper, Runnable body) {
        try {
            body.run();
        } catch (GameTestAssertException assertion) {
            throw assertion;
        } catch (RuntimeException failure) {
            Alina.LOGGER.error("a spike check failed unexpectedly", failure);
            helper.fail("the check threw " + failure);
        }
    }

    /** True when {@code modId} is not loaded. The caller then returns, because this has logged the skip and succeeded. */
    private static boolean skipWithout(GameTestHelper helper, String modId, String spike) {
        if (FabricLoader.getInstance().isModLoaded(modId)) {
            return false;
        }
        Alina.LOGGER.warn("{} is skipped: mod {} is not loaded", spike, modId);
        helper.succeed();
        return true;
    }

    /** A bundle with one commission of {@code category} and {@code kind}, with the proof {@code proof}. */
    private static String bundleWith(String category, String kind, String proof) {
        return """
                { "schema": 1,
                  "dialogues": { "alina:catalog/board": { "start": "hello", "nodes": { "hello": { "text": "Board." } } } },
                  "extensions": { "alina:guild": {
                    "settings": { "board_graph": "alina:catalog/board" },
                    "commissions": { "alina:catalog/spike": { "category": "%s", "kind": "%s", "name": "Spike",
                      "hint": "Spike.", "proof": %s } } } } }
                """.formatted(category, kind, proof);
    }

    private static String f3Bundle(String gateway) {
        return bundleWith("F", "feat", """
                { "type": "criterion", "trigger": "gateways:finish_gateway", "conditions": { "gateway": "%s" } }
                """.formatted(gateway));
    }

    private static String d3Bundle() {
        return bundleWith("D", "repeatable", """
                { "type": "item", "any_of": [
                  { "item": { "items": "farmersdelight:shepherds_pie" } },
                  { "item": { "items": "farmersdelight:stuffed_pumpkin" } } ] }
                """);
    }

    /** The proof of the spike commission, parsed by the server's content. A refusal fails the test with every problem. */
    private static Proof parsedProof(GameTestHelper helper, String json) {
        DataResult<ContentBundle> result = HaruhiServices.content().parseAndValidate(json);
        if (result.error().isPresent()) {
            helper.fail("the bundle was refused:\n" + result.error().get().message());
        }
        GuildContent guild = result.result().orElseThrow().extension(AlinaContent.GUILD)
                .orElseThrow(() -> new IllegalStateException("the bundle has no alina:guild section"));
        return guild.commissions().get(Identifier.parse("alina:catalog/spike")).proof();
    }

    private static Proof.Items items(GameTestHelper helper, Proof proof) {
        if (proof instanceof Proof.Items items) {
            return items;
        }
        helper.fail("the proof is not an item proof: " + proof);
        return null;
    }

    /** A criterion of {@code trigger} with vanilla condition JSON, parsed the way content parses it. */
    private static CriterionSpec spec(GameTestHelper helper, Identifier trigger, String conditions) {
        CriterionTrigger<?> type = BuiltInRegistries.TRIGGER_TYPES.getValue(trigger);
        RegistryOps<JsonElement> ops =
                RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().getServer().registryAccess());
        CriterionTriggerInstance instance = type.codec().parse(ops, JsonParser.parseString(conditions))
                .getOrThrow(message -> new IllegalStateException("conditions do not parse: " + message));
        return new CriterionSpec(trigger, instance, 1);
    }

    /** Gateways to Eternity's registry, read by reflection: the class is not on the compile classpath. */
    private static Class<?> gatewayRegistryClass(GameTestHelper helper) {
        try {
            return Class.forName(GATEWAY_REGISTRY);
        } catch (ClassNotFoundException e) {
            helper.fail("Gateways to Eternity is loaded but " + GATEWAY_REGISTRY + " is missing: " + e);
            return null;
        }
    }

    /** The gateway with {@code id}, or null when the registry holds none. */
    private static Object gateway(GameTestHelper helper, Identifier id) {
        Class<?> registryClass = gatewayRegistryClass(helper);
        try {
            Object registry = registryClass.getField("INSTANCE").get(null);
            return registryClass.getMethod("getValue", Identifier.class).invoke(registry, id);
        } catch (ReflectiveOperationException e) {
            helper.fail("the gateway registry could not be read: " + e);
            return null;
        }
    }

    private static Set<String> gatewayIds(GameTestHelper helper) {
        Class<?> registryClass = gatewayRegistryClass(helper);
        try {
            Object registry = registryClass.getField("INSTANCE").get(null);
            Set<String> ids = new TreeSet<>();
            for (Object key : (Iterable<?>) registryClass.getMethod("getKeys").invoke(registry)) {
                ids.add(key.toString());
            }
            return ids;
        } catch (ReflectiveOperationException e) {
            helper.fail("the gateway registry could not be read: " + e);
            return null;
        }
    }

    /**
     * Calls the trigger's own fire method for {@code gateway}, the one Gateways to Eternity calls for each player near a
     * gateway it completes. That method reaches the protected {@code SimpleCriterionTrigger.trigger}, which the mixin hooks.
     */
    private static void fireFinished(GameTestHelper helper, CriterionTrigger<?> trigger, ServerPlayer player,
            Object gateway) {
        for (Method method : trigger.getClass().getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (method.getName().equals("trigger") && params.length == 2 && params[0] == ServerPlayer.class
                    && params[1].getName().equals(GATEWAY)) {
                try {
                    method.invoke(trigger, player, gateway);
                } catch (ReflectiveOperationException e) {
                    helper.fail("the trigger threw when it fired: " + e.getCause());
                }
                return;
            }
        }
        helper.fail("no trigger(ServerPlayer, Gateway) method on " + trigger.getClass().getName());
    }

    private static Item itemOrFail(GameTestHelper helper, String id) {
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id));
        if (item.isEmpty()) {
            helper.fail("no item " + id + " on this server");
        }
        return item.get();
    }

    // S5: Gateways to Eternity's finish_gateway trigger (ALINA-PLAN §12).

    @GameTest(maxTicks = 100)
    public void s5GatewayIdsAreTheNineOfTheJar(GameTestHelper helper) {
        check(helper, () -> {
            if (skipWithout(helper, GATEWAYS, "S5 (gateway ids)")) {
                return;
            }
            Set<String> ids = gatewayIds(helper);
            Alina.LOGGER.info("S5 gateway ids on the server: {}", ids);
            helper.assertValueEqual(List.copyOf(ids), List.copyOf(new TreeSet<>(GATEWAY_IDS)),
                    "the gateways the registry holds");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void s5FinishGatewayIsARegisteredCriterionTrigger(GameTestHelper helper) {
        check(helper, () -> {
            if (skipWithout(helper, GATEWAYS, "S5 (trigger registration)")) {
                return;
            }
            helper.assertTrue(BuiltInRegistries.TRIGGER_TYPES.containsKey(FINISH_GATEWAY),
                    "gateways:finish_gateway is not in the trigger registry");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void s5F3ProofParsesAndNamesAGatewayTheRegistryHolds(GameTestHelper helper) {
        check(helper, () -> {
            if (skipWithout(helper, GATEWAYS, "S5 (F3 proof parse)")) {
                return;
            }
            Proof proof = parsedProof(helper, f3Bundle("gateways:overworldian_nights"));
            helper.assertTrue(proof instanceof Proof.Criterion, "the F3 proof is not a criterion: " + proof);
            CriterionSpec spec = ((Proof.Criterion) proof).criterion();
            helper.assertValueEqual(spec.trigger(), FINISH_GATEWAY, "the F3 trigger");
            helper.assertValueEqual(spec.count(), 1, "the F3 count");
            helper.assertTrue(gateway(helper, OVERWORLDIAN_NIGHTS) != null,
                    "the gateway the proof names is not in the registry");

            // Not a pass or a fail: records what the reader does with a gateway id the registry does not hold.
            DataResult<ContentBundle> typo = HaruhiServices.content().parseAndValidate(
                    f3Bundle("gateways:no_such_gateway"));
            Alina.LOGGER.info("S5 finding: a proof naming gateways:no_such_gateway is {}",
                    typo.error().isPresent() ? "refused: " + typo.error().get().message() : "accepted");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void s5FinishGatewayReachesTheWatcherOfTheGatewayItNames(GameTestHelper helper) {
        check(helper, () -> {
            if (skipWithout(helper, GATEWAYS, "S5 (watcher)")) {
                return;
            }
            CriterionTrigger<?> trigger = BuiltInRegistries.TRIGGER_TYPES.getValue(FINISH_GATEWAY);
            helper.assertTrue(trigger != null, "gateways:finish_gateway is not a registered trigger");
            Object named = gateway(helper, OVERWORLDIAN_NIGHTS);
            Object other = gateway(helper, EMERALD_GROVE);
            helper.assertTrue(named != null && other != null, "a gateway the test names is not in the registry");

            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            AtomicInteger seen = new AtomicInteger();
            CriterionWatchers.Watch watch = CriterionWatchers.watch(player.getUUID(),
                    spec(helper, FINISH_GATEWAY, "{ \"gateway\": \"gateways:overworldian_nights\" }"),
                    finisher -> seen.incrementAndGet());
            fireFinished(helper, trigger, player, other);
            helper.assertValueEqual(seen.get(), 0, "a gateway the proof does not name");
            fireFinished(helper, trigger, player, named);
            helper.assertValueEqual(seen.get(), 1, "the gateway the proof names");
            watch.close();
            helper.succeed();
        });
    }

    // D3: Farmer's Delight's shepherd's pie and stuffed pumpkin (ALINA-PLAN §14).

    @GameTest(maxTicks = 100)
    public void d3ProofIsMetByTheServedDishesAndNotByAFeastBlockOrAnotherDish(GameTestHelper helper) {
        check(helper, () -> {
            if (skipWithout(helper, FARMERS_DELIGHT, "D3")) {
                return;
            }
            Proof.Items proof = items(helper, parsedProof(helper, d3Bundle()));
            Inventory inventory = helper.makeMockServerPlayerInLevel().getInventory();
            for (String dish : List.of("farmersdelight:shepherds_pie", "farmersdelight:stuffed_pumpkin")) {
                inventory.setItem(0, new ItemStack(itemOrFail(helper, dish)));
                helper.assertTrue(ItemProofPlanner.plan(inventory, proof).isPresent(), dish + " does not meet D3");
            }
            // The feast blocks are placed blocks, and a different dish is no D3 dish either.
            for (String miss : List.of("farmersdelight:shepherds_pie_block", "farmersdelight:stuffed_pumpkin_block",
                    "farmersdelight:pumpkin_soup")) {
                inventory.setItem(0, new ItemStack(itemOrFail(helper, miss)));
                helper.assertTrue(ItemProofPlanner.plan(inventory, proof).isEmpty(), miss + " meets D3");
            }
            helper.succeed();
        });
    }

    // F2: the discs of the creeper drop tag that no chest loot table drops (ALINA-PLAN §14).

    @GameTest(maxTicks = 100)
    public void f2ListIsTheCreeperDiscsNoChestTableDrops(GameTestHelper helper) {
        check(helper, () -> {
            MinecraftServer server = helper.getLevel().getServer();
            ResourceManager resources = server.getResourceManager();
            // The vanilla tables only: other mods on the dev runtime add chest tables of their own.
            Map<Identifier, Resource> files = resources.listResources("loot_table", id ->
                    id.getNamespace().equals("minecraft") && id.getPath().startsWith("loot_table/chests/")
                            && id.getPath().endsWith(".json"));
            helper.assertValueEqual(files.size(), 56,
                    "the vanilla chest loot tables (data/minecraft/loot_table/chests/**)");
            Set<String> chestItems = new HashSet<>();
            Set<Identifier> visited = new HashSet<>();
            for (Identifier file : files.keySet()) {
                String path = file.getPath().substring("loot_table/".length(),
                        file.getPath().length() - ".json".length());
                lootItems(helper, resources, Identifier.fromNamespaceAndPath(file.getNamespace(), path),
                        chestItems, visited);
            }
            Alina.LOGGER.info("F2 read {} chest loot tables", files.size());

            HolderSet.Named<Item> tag = server.registryAccess().lookupOrThrow(Registries.ITEM)
                    .get(TagKey.create(Registries.ITEM, Identifier.parse("minecraft:creeper_drop_music_discs")))
                    .orElseThrow(() -> new IllegalStateException("no minecraft:creeper_drop_music_discs tag"));
            List<String> discs = new ArrayList<>();
            for (Holder<Item> holder : tag) {
                discs.add(holder.getRegisteredName());
            }
            Alina.LOGGER.info("F2 creeper drop discs: {}; chest discs: {}", discs,
                    discs.stream().filter(chestItems::contains).sorted().toList());

            Set<String> never = new TreeSet<>();
            for (String disc : discs) {
                if (!chestItems.contains(disc)) {
                    never.add(disc);
                }
            }
            helper.assertValueEqual(List.copyOf(never), F2_DISCS, "the creeper drop discs no chest table drops");
            helper.succeed();
        });
    }

    /**
     * Adds every item that the loot table {@code table} can drop, following its entries through their children, through
     * loot_table references and through tags. A loot entry type that this does not read fails the test.
     */
    private static void lootItems(GameTestHelper helper, ResourceManager resources, Identifier table, Set<String> out,
            Set<Identifier> visited) {
        if (!visited.add(table)) {
            return;
        }
        Optional<Resource> resource = resources.getResource(
                Identifier.fromNamespaceAndPath(table.getNamespace(), "loot_table/" + table.getPath() + ".json"));
        if (resource.isEmpty()) {
            helper.fail("loot table " + table + " is referenced but not on the server");
            return;
        }
        JsonObject json = readObject(helper, resource.get(), table);
        if (!json.has("pools")) {
            return;
        }
        for (JsonElement pool : json.getAsJsonArray("pools")) {
            if (pool.getAsJsonObject().has("entries")) {
                for (JsonElement entry : pool.getAsJsonObject().getAsJsonArray("entries")) {
                    lootEntry(helper, resources, table, entry.getAsJsonObject(), out, visited);
                }
            }
        }
    }

    private static void lootEntry(GameTestHelper helper, ResourceManager resources, Identifier table, JsonObject entry,
            Set<String> out, Set<Identifier> visited) {
        String type = entry.get("type").getAsString();
        switch (type) {
            case "minecraft:item" -> out.add(Identifier.parse(entry.get("name").getAsString()).toString());
            case "minecraft:tag" -> {
                Identifier tagId = Identifier.parse(entry.get("name").getAsString());
                HolderSet.Named<Item> tag = BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, tagId))
                        .orElseThrow(() -> new IllegalStateException("no item tag " + tagId + " on the server"));
                for (Holder<Item> holder : tag) {
                    out.add(holder.getRegisteredName());
                }
            }
            case "minecraft:loot_table" -> lootItems(helper, resources,
                    Identifier.parse(entry.get("value").getAsString()), out, visited);
            case "minecraft:alternatives", "minecraft:group", "minecraft:sequence" -> {
                if (entry.has("children")) {
                    JsonArray children = entry.getAsJsonArray("children");
                    for (JsonElement child : children) {
                        lootEntry(helper, resources, table, child.getAsJsonObject(), out, visited);
                    }
                }
            }
            case "minecraft:empty" -> {
                // Drops nothing.
            }
            default -> helper.fail("loot table " + table + " has an entry type this check does not read: " + type);
        }
    }

    private static JsonObject readObject(GameTestHelper helper, Resource resource, Identifier table) {
        try (var reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            helper.fail("loot table " + table + " could not be read: " + e);
            return null;
        }
    }
}
