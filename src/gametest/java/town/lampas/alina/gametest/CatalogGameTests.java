package town.lampas.alina.gametest;

import com.mojang.serialization.DataResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.component.Bees;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.component.OminousBottleAmplifier;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import town.lampas.alina.Alina;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.content.Commission;
import town.lampas.alina.content.GuildContent;
import town.lampas.alina.content.Proof;
import town.lampas.alina.guild.proof.ItemPlan;
import town.lampas.alina.guild.proof.ItemProofPlanner;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;

/**
 * Spike S4 (ALINA-PLAN §12, catalog §14) and the item proof planner (§8.3), tested on items made in game.
 *
 * <p>Each catalog item proof is written as Lampas content writes it, inside a bundle with one {@code alina:guild}
 * commission under {@code fixtures/catalog/}, and parsed with the server's registries. Its item is made the way the game
 * makes it where the item carries data. The data-bearing proofs also get near misses, which must not be met. The criterion
 * proofs must parse. D3 (Farmer's Delight) is not in the dev environment and is not tested.
 */
public final class CatalogGameTests {
    private static final String FIXTURES = "/alina/fixtures/catalog/";
    private static final Identifier PLANNER = Alina.id("catalog/planner");
    private static final BlockPos SPOT = new BlockPos(1, 2, 1);

    /** A bundle with one repeatable commission in category C, {@code id} with the proof {@code proof}. */
    private static String bundleWith(String id, String proof) {
        return """
                { "schema": 1,
                  "dialogues": { "alina:catalog/board": { "start": "hello", "nodes": { "hello": { "text": "Board." } } } },
                  "extensions": { "alina:guild": {
                    "settings": { "board_graph": "alina:catalog/board" },
                    "commissions": { "%s": { "category": "C", "kind": "repeatable", "name": "Test", "hint": "Test.",
                      "proof": %s } } } } }
                """.formatted(id, proof);
    }

    private static String fixture(String name) {
        try (InputStream in = CatalogGameTests.class.getResourceAsStream(FIXTURES + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The bundle parsed by the server, or the failure with every problem listed. */
    private static ContentBundle parse(String json) {
        DataResult<ContentBundle> result = HaruhiServices.content().parseAndValidate(json);
        if (result.error().isPresent()) {
            throw new IllegalStateException("the bundle was refused:\n" + result.error().get().message());
        }
        return result.result().orElseThrow();
    }

    /** The proof of commission {@code id} in a parsed bundle. */
    private static Proof proofOf(ContentBundle bundle, String id) {
        GuildContent guild = bundle.extension(AlinaContent.GUILD)
                .orElseThrow(() -> new IllegalStateException("the bundle has no alina:guild section"));
        Commission commission = guild.commissions().get(Identifier.parse(id));
        if (commission == null) {
            throw new IllegalStateException("the bundle has no commission " + id);
        }
        return commission.proof();
    }

    /** The commission's proof from a fixture, parsed by the server. */
    private static Proof fixtureProof(String file, String id) {
        return proofOf(parse(fixture(file)), id);
    }

    /** The proof of the planner's test commission, an item proof, parsed by the server. */
    private static Proof.Items itemProof(String proof) {
        return items(proofOf(parse(bundleWith(PLANNER.toString(), proof)), PLANNER.toString()));
    }

    private static Proof.Items items(Proof proof) {
        if (proof instanceof Proof.Items items) {
            return items;
        }
        throw new IllegalStateException("the proof is not an item proof: " + proof);
    }

    /**
     * Runs {@code check} once haruhi's startup activation has finished, then succeeds. A failure inside the check fails
     * this test: an exception other than an assertion would stop the whole GameTest server.
     */
    private static void whenStarted(GameTestHelper helper, Runnable check) {
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(HaruhiServices.content().isStartupComplete(),
                        "startup activation has not finished"))
                .thenExecute(() -> {
                    try {
                        check.run();
                    } catch (GameTestAssertException assertion) {
                        throw assertion;
                    } catch (RuntimeException failure) {
                        Alina.LOGGER.error("a catalog check failed unexpectedly", failure);
                        helper.fail("the check threw " + failure);
                    }
                })
                .thenSucceed();
    }

    private static void assertMet(GameTestHelper helper, Inventory inventory, Proof.Items proof, String what) {
        helper.assertTrue(ItemProofPlanner.plan(inventory, proof).isPresent(), what + " does not meet the proof");
    }

    private static void assertNotMet(GameTestHelper helper, Inventory inventory, Proof.Items proof, String what) {
        helper.assertTrue(ItemProofPlanner.plan(inventory, proof).isEmpty(), what + " meets the proof");
    }

    /** A survival mock player that is not in the server's player list. */
    private static Player mockPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayer(GameType.SURVIVAL);
    }

    /** The component types a stack sets on itself, which its item does not set by default. */
    private static Set<DataComponentType<?>> patchedComponents(ItemStack stack) {
        Set<DataComponentType<?>> set = new HashSet<>();
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : stack.getComponentsPatch().entrySet()) {
            set.add(entry.getKey());
        }
        return set;
    }

    private static ItemStack silkTouchPickaxe(MinecraftServer server) {
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        tool.enchant(server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH), 1);
        return tool;
    }

    /** Breaks the block at {@code spot} with a Silk Touch tool through its own loot table, and returns its drops. */
    private static List<ItemStack> breakWithSilkTouch(GameTestHelper helper, BlockPos spot) {
        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(spot);
        BlockEntity blockEntity = level.getBlockEntity(absolute);
        return Block.getDrops(level.getBlockState(absolute), level, absolute, blockEntity, null,
                silkTouchPickaxe(level.getServer()));
    }

    /** A bee nest at {@code honeyLevel} with {@code bees} bees in it, broken with Silk Touch. */
    private static ItemStack beeNestDrop(GameTestHelper helper, BlockPos spot, int honeyLevel, int bees) {
        helper.setBlock(spot, Blocks.BEE_NEST.defaultBlockState().setValue(BeehiveBlock.HONEY_LEVEL, honeyLevel));
        BeehiveBlockEntity hive = helper.getBlockEntity(spot, BeehiveBlockEntity.class);
        for (int i = 0; i < bees; i++) {
            hive.storeBee(BeehiveBlockEntity.Occupant.create(0));
        }
        List<ItemStack> drops = breakWithSilkTouch(helper, spot);
        helper.assertValueEqual(drops.size(), 1, "the drops of a bee nest");
        return drops.get(0);
    }

    /** A stew made in the 2x2 grid of a bowl, two mushrooms and {@code flower}, through the server's recipe lookup. */
    private static ItemStack craftStew(GameTestHelper helper, Item flower, String recipe) {
        ServerLevel level = helper.getLevel();
        CraftingInput input = CraftingInput.of(2, 2, List.of(new ItemStack(Items.BOWL), new ItemStack(Items.BROWN_MUSHROOM),
                new ItemStack(Items.RED_MUSHROOM), new ItemStack(flower)));
        RecipeHolder<CraftingRecipe> holder = level.getServer().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, level)
                .orElseThrow(() -> new IllegalStateException("no crafting recipe matches the grid"));
        helper.assertValueEqual(holder.id().identifier(), Identifier.parse(recipe), "the recipe the grid crafted");
        return holder.value().assemble(input);
    }

    // S4: one test per commission with an item proof.

    @GameTest(maxTicks = 400)
    public void a1UntouchableGrainIsMetByEitherSuspiciousBlock(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = items(fixtureProof("a1_untouchable_grain.json", "alina:catalog/a1_untouchable_grain"));
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, new ItemStack(Items.SUSPICIOUS_SAND));
            assertMet(helper, inventory, proof, "suspicious sand");
            inventory.setItem(0, new ItemStack(Items.SUSPICIOUS_GRAVEL));
            assertMet(helper, inventory, proof, "suspicious gravel");
            inventory.setItem(0, new ItemStack(Items.SAND));
            assertNotMet(helper, inventory, proof, "plain sand");
        });
    }

    @GameTest(maxTicks = 400)
    public void a2BottledShriekerIsAnyShriekerEvenFromASilkTouchDrop(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = items(fixtureProof("a2_bottled_shrieker.json", "alina:catalog/a2_bottled_shrieker"));
            helper.setBlock(SPOT, Blocks.SCULK_SHRIEKER.defaultBlockState().setValue(SculkShriekerBlock.CAN_SUMMON, true));
            List<ItemStack> drops = breakWithSilkTouch(helper, SPOT);
            helper.assertValueEqual(drops.size(), 1, "the drops of a sculk shrieker");
            ItemStack drop = drops.get(0);
            // The silk touch drop has no copy_state or copy_components, so it carries nothing its item does not.
            Alina.LOGGER.info("S4 A2 sculk shrieker drop (can_summon true), components the stack sets: {}",
                    patchedComponents(drop));
            helper.assertTrue(ItemStack.isSameItemSameComponents(drop, new ItemStack(Items.SCULK_SHRIEKER)),
                    "the silk touch drop carries data: " + patchedComponents(drop));
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, drop);
            assertMet(helper, inventory, proof, "the silk touch drop of a shrieker");
            inventory.setItem(0, new ItemStack(Items.SCULK_SHRIEKER));
            assertMet(helper, inventory, proof, "a shrieker from the creative menu");
        });
    }

    @GameTest(maxTicks = 400)
    public void a3SubterraneanBlossomIsASporeBlossom(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = items(fixtureProof("a3_subterranean_blossom.json", "alina:catalog/a3_subterranean_blossom"));
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, new ItemStack(Items.SPORE_BLOSSOM));
            assertMet(helper, inventory, proof, "a spore blossom");
            inventory.setItem(0, new ItemStack(Items.AZALEA));
            assertNotMet(helper, inventory, proof, "an azalea");
        });
    }

    @GameTest(maxTicks = 400)
    public void a4PreservedColonyIsAFullBeeNestBrokenWithSilkTouch(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = items(fixtureProof("a4_preserved_colony.json", "alina:catalog/a4_preserved_colony"));
            ItemStack drop = beeNestDrop(helper, SPOT, 5, 3);
            BlockItemStateProperties state = drop.get(DataComponents.BLOCK_STATE);
            Bees bees = drop.get(DataComponents.BEES);
            helper.assertTrue(bees != null && bees.bees().size() == 3, "the drop holds " + bees);
            helper.assertTrue(state != null && state.properties().equals(Map.of("honey_level", "5")),
                    "the drop's block state is " + state);
            Alina.LOGGER.info("S4 A4 bee nest drop (honey 5, 3 bees), components the stack sets: {}",
                    patchedComponents(drop));
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, drop);
            assertMet(helper, inventory, proof, "a bee nest at honey level 5 with three bees");

            ItemStack twoBees = beeNestDrop(helper, new BlockPos(2, 2, 1), 5, 2);
            inventory.setItem(0, twoBees);
            assertNotMet(helper, inventory, proof, "a bee nest with two bees");
            ItemStack honeyFour = beeNestDrop(helper, new BlockPos(3, 2, 1), 4, 3);
            inventory.setItem(0, honeyFour);
            assertNotMet(helper, inventory, proof, "a bee nest at honey level 4");
        });
    }

    @GameTest(maxTicks = 400)
    public void b1GlassblowersLightningIsASkeletonSkullOrACreeperHead(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = items(fixtureProof("b1_glassblowers_lightning.json", "alina:catalog/b1_glassblowers_lightning"));
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, new ItemStack(Items.SKELETON_SKULL));
            assertMet(helper, inventory, proof, "a skeleton skull");
            inventory.setItem(0, new ItemStack(Items.CREEPER_HEAD));
            assertMet(helper, inventory, proof, "a creeper head");
            inventory.setItem(0, new ItemStack(Items.ZOMBIE_HEAD));
            assertNotMet(helper, inventory, proof, "a zombie head");
        });
    }

    @GameTest(maxTicks = 400)
    public void b2FroglightTriadNeedsOneOfEachColour(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = items(fixtureProof("b2_froglight_triad.json", "alina:catalog/b2_froglight_triad"));
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, new ItemStack(Items.OCHRE_FROGLIGHT));
            inventory.setItem(1, new ItemStack(Items.VERDANT_FROGLIGHT));
            assertNotMet(helper, inventory, proof, "two froglights");
            inventory.setItem(2, new ItemStack(Items.PEARLESCENT_FROGLIGHT));
            assertMet(helper, inventory, proof, "one of each froglight");
        });
    }

    @GameTest(maxTicks = 400)
    public void b3ScreamingGoatHornIsAScreamingInstrument(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = items(fixtureProof("b3_screaming_goat.json", "alina:catalog/b3_screaming_goat"));
            Goat goat = helper.spawn(EntityTypes.GOAT, SPOT);
            goat.setScreamingGoat(true);
            ItemStack horn = goat.createHorn();
            Alina.LOGGER.info("S4 B3 horn from a screaming goat: {}", horn.get(DataComponents.INSTRUMENT));
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, horn);
            assertMet(helper, inventory, proof, "a horn from a screaming goat");

            goat.setScreamingGoat(false);
            ItemStack regular = goat.createHorn();
            Alina.LOGGER.info("S4 B3 near miss, horn from a plain goat: {}", regular.get(DataComponents.INSTRUMENT));
            inventory.setItem(0, regular);
            assertNotMet(helper, inventory, proof, "a horn from a plain goat");
        });
    }

    @GameTest(maxTicks = 400)
    public void b4WithersGardenIsAWitherRose(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = items(fixtureProof("b4_withers_garden.json", "alina:catalog/b4_withers_garden"));
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, new ItemStack(Items.WITHER_ROSE));
            assertMet(helper, inventory, proof, "a wither rose");
            inventory.setItem(0, new ItemStack(Items.POPPY));
            assertNotMet(helper, inventory, proof, "a poppy");
        });
    }

    @GameTest(maxTicks = 400)
    public void b6LightningFungusIsAWitherStew(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = items(fixtureProof("b6_lightning_fungus.json", "alina:catalog/b6_lightning_fungus"));
            ItemStack wither = craftStew(helper, Items.WITHER_ROSE, "minecraft:suspicious_stew_from_wither_rose");
            Alina.LOGGER.info("S4 B6 wither stew: {}", wither.get(DataComponents.SUSPICIOUS_STEW_EFFECTS));
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, wither);
            assertMet(helper, inventory, proof, "a suspicious stew from a wither rose");

            ItemStack poppy = craftStew(helper, Items.POPPY, "minecraft:suspicious_stew_from_poppy");
            inventory.setItem(0, poppy);
            assertNotMet(helper, inventory, proof, "a suspicious stew from a poppy");
        });
    }

    @GameTest(maxTicks = 400)
    public void c1ExtinctFloraIsATorchflowerAndAPitcherPlant(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = items(fixtureProof("c1_extinct_flora.json", "alina:catalog/c1_extinct_flora"));
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, new ItemStack(Items.TORCHFLOWER));
            assertNotMet(helper, inventory, proof, "a torchflower alone");
            inventory.setItem(1, new ItemStack(Items.PITCHER_PLANT));
            assertMet(helper, inventory, proof, "a torchflower and a pitcher plant");
        });
    }

    @GameTest(maxTicks = 400)
    public void c2OxidizedMetallurgyIsWaxedOxidizedChiseledCopper(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = items(fixtureProof("c2_oxidized_metallurgy.json", "alina:catalog/c2_oxidized_metallurgy"));
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, new ItemStack(Items.CHISELED_COPPER.waxed().pick(WeatheringCopper.WeatherState.OXIDIZED)));
            assertMet(helper, inventory, proof, "waxed oxidized chiseled copper");
            inventory.setItem(0, new ItemStack(Items.CHISELED_COPPER.weathering().pick(WeatheringCopper.WeatherState.OXIDIZED)));
            assertNotMet(helper, inventory, proof, "unwaxed oxidized chiseled copper");
        });
    }

    @GameTest(maxTicks = 400)
    public void d1MasterOfTheShellIsAStrongTurtleMasterPotion(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = items(fixtureProof("d1_master_of_the_shell.json", "alina:catalog/d1_master_of_the_shell"));
            PotionBrewing brewing = helper.getLevel().getServer().potionBrewing();
            ItemStack water = PotionContents.createItemStack(Items.POTION, Potions.WATER);
            // PotionBrewing.mix takes the ingredient first and the potion second.
            ItemStack awkward = brewing.mix(new ItemStack(Items.NETHER_WART), water);
            ItemStack turtle = brewing.mix(new ItemStack(Items.TURTLE_HELMET), awkward);
            ItemStack strong = brewing.mix(new ItemStack(Items.GLOWSTONE_DUST), turtle);
            helper.assertValueEqual(potionOf(awkward), Optional.of(Potions.AWKWARD), "the awkward potion");
            helper.assertValueEqual(potionOf(turtle), Optional.of(Potions.TURTLE_MASTER), "the turtle master potion");
            helper.assertValueEqual(potionOf(strong), Optional.of(Potions.STRONG_TURTLE_MASTER),
                    "the potion of the glowstone mix");
            Alina.LOGGER.info("S4 D1 strong turtle master potion components: {}", patchedComponents(strong));
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, strong);
            assertMet(helper, inventory, proof, "a strong turtle master potion");
            inventory.setItem(0, turtle);
            assertNotMet(helper, inventory, proof, "a plain turtle master potion");
        });
    }

    /**
     * A captain pillager's loot table gives an ominous bottle whose amplifier is uniform from 0 to 4. The loot is rolled
     * with seeds until one roll gives amplifier 4 and another gives 3.
     */
    @GameTest(maxTicks = 400)
    public void d2InfiltrationBrewIsAnOminousBottleOfAmplifierFour(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = items(fixtureProof("d2_infiltration_brew.json", "alina:catalog/d2_infiltration_brew"));
            MinecraftServer server = helper.getLevel().getServer();
            Pillager captain = helper.spawn(EntityTypes.PILLAGER, SPOT);
            // A raid captain wears the ominous banner and leads its patrol: Raider.isCaptain needs both.
            captain.setPatrolLeader(true);
            captain.setItemSlot(EquipmentSlot.HEAD,
                    Raid.getOminousBannerInstance(helper.getLevel().registryAccess().lookupOrThrow(Registries.BANNER_PATTERN)));
            helper.assertTrue(captain.isCaptain(), "the pillager is not a captain");
            LootParams params = new LootParams.Builder(helper.getLevel())
                    .withParameter(LootContextParams.THIS_ENTITY, captain)
                    .withParameter(LootContextParams.ORIGIN, captain.position())
                    .withParameter(LootContextParams.DAMAGE_SOURCE, helper.getLevel().damageSources().generic())
                    .create(LootContextParamSets.ENTITY);
            LootTable table = server.reloadableRegistries().getLootTable(
                    ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse("minecraft:entities/pillager")));
            ItemStack fourth = null;
            ItemStack third = null;
            long fourthSeed = -1;
            long thirdSeed = -1;
            for (long seed = 0; seed < 1000 && (fourth == null || third == null); seed++) {
                for (ItemStack drop : table.getRandomItems(params, seed)) {
                    if (!drop.is(Items.OMINOUS_BOTTLE)) {
                        continue;
                    }
                    int amplifier = amplifierOf(drop);
                    if (amplifier == 4 && fourth == null) {
                        fourth = drop;
                        fourthSeed = seed;
                    } else if (amplifier == 3 && third == null) {
                        third = drop;
                        thirdSeed = seed;
                    }
                }
            }
            helper.assertTrue(fourth != null && third != null, "no seed below 1000 gave amplifiers 4 and 3");
            Alina.LOGGER.info("S4 D2 loot seeds: amplifier 4 at seed {}, amplifier 3 at seed {}", fourthSeed, thirdSeed);
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, fourth);
            assertMet(helper, inventory, proof, "an ominous bottle of amplifier 4 from the captain's loot");
            inventory.setItem(0, third);
            assertNotMet(helper, inventory, proof, "an ominous bottle of amplifier 3");
        });
    }

    private static int amplifierOf(ItemStack bottle) {
        OminousBottleAmplifier amplifier = bottle.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
        if (amplifier == null) {
            throw new IllegalStateException("the ominous bottle has no amplifier: " + bottle);
        }
        return amplifier.value();
    }

    @GameTest(maxTicks = 400)
    public void d2InfiltrationBrewIsNotMetByABottleWithoutAnAmplifier(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = items(fixtureProof("d2_infiltration_brew.json", "alina:catalog/d2_infiltration_brew"));
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, new ItemStack(Items.OMINOUS_BOTTLE));
            assertNotMet(helper, inventory, proof, "an ominous bottle with no amplifier");
        });
    }

    /**
     * F1 is written in code because its proof names the lodestone's absolute position. The compass is used on a placed
     * lodestone the way a player uses it, and the proof names that lodestone.
     */
    @GameTest(maxTicks = 400)
    public void f1CalibratedNeedleIsACompassUsedOnTheNamedLodestone(GameTestHelper helper) {
        whenStarted(helper, () -> {
            ServerLevel level = helper.getLevel();
            BlockPos lodestone = helper.absolutePos(SPOT);
            BlockPos other = helper.absolutePos(new BlockPos(3, 2, 1));
            String dimension = level.dimension().identifier().toString();
            // tracked is left out: the reader refuses "tracked": true, which only repeats its default.
            String proof = """
                    { "type": "item", "any_of": [ { "item": { "items": "minecraft:compass",
                      "components": { "minecraft:lodestone_tracker": { "target": { "dimension": "%s", "pos": [%d, %d, %d] } } } } } ] }
                    """.formatted(dimension, lodestone.getX(), lodestone.getY(), lodestone.getZ());
            Proof.Items items = itemProof(proof);

            Player player = mockPlayer(helper);
            ItemStack compass = usedOnLodestone(helper, player, SPOT, lodestone);
            LodestoneTracker tracker = compass.get(DataComponents.LODESTONE_TRACKER);
            helper.assertTrue(tracker != null && tracker.tracked(), "the compass tracks no lodestone: " + tracker);
            Alina.LOGGER.info("S4 F1 compass tracker: {}, extra components {}", tracker, patchedComponents(compass));
            player.getInventory().setItem(0, compass);
            assertMet(helper, player.getInventory(), items, "a compass used on the named lodestone");

            ItemStack elsewhere = usedOnLodestone(helper, player, new BlockPos(3, 2, 1), other);
            player.getInventory().setItem(0, elsewhere);
            assertNotMet(helper, player.getInventory(), items, "a compass used on another lodestone");
        });
    }

    /**
     * Whether the lodestone tracker's {@code tracked} is part of the exact match. The compass the game makes is tracked
     * (true). The reader refuses {@code "tracked": true} as a repeat of the default, so the proof leaves it out and the
     * exact match still needs tracked to be true: {@code "tracked": false} does not match.
     */
    @GameTest(maxTicks = 400)
    public void f1TrackedIsAnsweredByTheExactMatch(GameTestHelper helper) {
        whenStarted(helper, () -> {
            ServerLevel level = helper.getLevel();
            BlockPos lodestone = helper.absolutePos(SPOT);
            String target = "\"target\": { \"dimension\": \"" + level.dimension().identifier() + "\", \"pos\": ["
                    + lodestone.getX() + ", " + lodestone.getY() + ", " + lodestone.getZ() + "] }";
            Player player = mockPlayer(helper);
            player.getInventory().setItem(0, usedOnLodestone(helper, player, SPOT, lodestone));
            Inventory inventory = player.getInventory();
            String compass = "{ \"type\": \"item\", \"any_of\": [ { \"item\": { \"items\": \"minecraft:compass\","
                    + " \"components\": { \"minecraft:lodestone_tracker\": { %s%s } } } } ] }";
            Proof.Items withoutTracked = itemProof(compass.formatted(target, ""));
            Proof.Items untracked = itemProof(compass.formatted(target, ", \"tracked\": false"));
            assertMet(helper, inventory, withoutTracked, "the proof without tracked");
            assertNotMet(helper, inventory, untracked, "the proof with tracked false");

            String refused = bundleWith("alina:catalog/planner", compass.formatted(target, ", \"tracked\": true"));
            String message = HaruhiServices.content().parseAndValidate(refused).error().map(error -> error.message())
                    .orElse("");
            helper.assertTrue(message.contains("tracked") && message.contains("only repeats its default"),
                    "tracked true was not refused as a default: " + message);
        });
    }

    private static Optional<Holder<Potion>> potionOf(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return contents == null ? Optional.empty() : contents.potion();
    }

    /** Places a lodestone at {@code spot} and uses a compass held in the player's main hand on it. */
    private static ItemStack usedOnLodestone(GameTestHelper helper, Player player, BlockPos spot, BlockPos absolute) {
        helper.setBlock(spot, Blocks.LODESTONE);
        ItemStack compass = new ItemStack(Items.COMPASS);
        player.setItemInHand(InteractionHand.MAIN_HAND, compass);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false);
        Items.COMPASS.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        return player.getItemInHand(InteractionHand.MAIN_HAND);
    }

    @GameTest(maxTicks = 400)
    public void f2DiscJockeysGambleIsADiscInTheCreeperDropTag(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = items(fixtureProof("f2_disc_jockeys_gamble.json", "alina:catalog/f2_disc_jockeys_gamble"));
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, new ItemStack(Items.MUSIC_DISC_CAT));
            assertMet(helper, inventory, proof, "music disc cat, a creeper drop");
            inventory.setItem(0, new ItemStack(Items.MUSIC_DISC_PIGSTEP));
            assertNotMet(helper, inventory, proof, "music disc pigstep, outside the tag");
        });
    }

    @GameTest(maxTicks = 400)
    public void f4HeavyCoreIsAHeavyCore(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = items(fixtureProof("f4_heavy_core.json", "alina:catalog/f4_heavy_core"));
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, new ItemStack(Items.HEAVY_CORE));
            assertMet(helper, inventory, proof, "a heavy core");
            inventory.setItem(0, new ItemStack(Items.IRON_BLOCK));
            assertNotMet(helper, inventory, proof, "an iron block");
        });
    }

    // S4: the criterion proofs parse with the server's validation (vanilla's conditions and the Alina triggers).

    @GameTest(maxTicks = 400)
    public void criterionProofsA5B5E1E2ParseAndKeepTheirTriggers(GameTestHelper helper) {
        whenStarted(helper, () -> {
            checkCriterion(helper, "a5_open_water_salvage.json", "alina:catalog/a5_open_water_salvage",
                    "minecraft:fishing_rod_hooked");
            checkCriterion(helper, "b5_zombie_doctor.json", "alina:catalog/b5_zombie_doctor",
                    "minecraft:cured_zombie_villager");
            checkCriterion(helper, "e1_longshot_marksman.json", "alina:catalog/e1_longshot_marksman",
                    "minecraft:player_killed_entity");
            checkCriterion(helper, "e2_wind_reversal.json", "alina:catalog/e2_wind_reversal",
                    "minecraft:player_killed_entity");
        });
    }

    private static void checkCriterion(GameTestHelper helper, String file, String id, String trigger) {
        Proof proof = fixtureProof(file, id);
        helper.assertTrue(proof instanceof Proof.Criterion, id + " is not a criterion proof: " + proof);
        helper.assertValueEqual(((Proof.Criterion) proof).criterion().trigger(), Identifier.parse(trigger),
                id + " trigger");
    }

    // The item proof planner (ALINA-PLAN §8.3), on a mock player's inventory.

    @GameTest(maxTicks = 400)
    public void allOfTheThreeFroglightsRemovesExactlyOneOfEach(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = itemProof("""
                    { "type": "item", "all_of": [ { "item": { "items": "minecraft:ochre_froglight" } },
                      { "item": { "items": "minecraft:verdant_froglight" } },
                      { "item": { "items": "minecraft:pearlescent_froglight" } } ] }
                    """);
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(3, new ItemStack(Items.OCHRE_FROGLIGHT));
            inventory.setItem(7, new ItemStack(Items.VERDANT_FROGLIGHT, 2));
            inventory.setItem(9, new ItemStack(Items.PEARLESCENT_FROGLIGHT));
            ItemPlan plan = ItemProofPlanner.plan(inventory, proof).orElseThrow();
            helper.assertValueEqual(plan.takes(), List.of(new ItemPlan.Take(3, 1), new ItemPlan.Take(7, 1),
                    new ItemPlan.Take(9, 1)), "the takes");
            List<ItemStack> removed = plan.apply(inventory);
            helper.assertTrue(removed.size() == 3 && removed.get(0).is(Items.OCHRE_FROGLIGHT)
                    && removed.get(1).is(Items.VERDANT_FROGLIGHT) && removed.get(2).is(Items.PEARLESCENT_FROGLIGHT)
                    && removed.stream().allMatch(stack -> stack.getCount() == 1), "removed: " + removed);
            helper.assertTrue(inventory.getItem(3).isEmpty(), "the ochre froglight is still there");
            helper.assertValueEqual(inventory.getItem(7).getCount(), 1, "the verdant froglights left");
            helper.assertTrue(inventory.getItem(9).isEmpty(), "the pearlescent froglight is still there");
        });
    }

    @GameTest(maxTicks = 400)
    public void anAllOfWhereGreedyFailsIsMetByAMatching(GameTestHelper helper) {
        whenStarted(helper, () -> {
            // Entry 1 takes any froglight and entry 2 needs the ochre. Greedy gives the ochre (slot 0) to entry 1.
            Proof.Items proof = itemProof("""
                    { "type": "item", "all_of": [
                      { "item": { "items": [ "minecraft:ochre_froglight", "minecraft:verdant_froglight",
                                             "minecraft:pearlescent_froglight" ] } },
                      { "item": { "items": "minecraft:ochre_froglight" } } ] }
                    """);
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, new ItemStack(Items.OCHRE_FROGLIGHT));
            inventory.setItem(1, new ItemStack(Items.PEARLESCENT_FROGLIGHT));
            helper.assertValueEqual(ItemProofPlanner.plan(inventory, proof).orElseThrow().takes(),
                    List.of(new ItemPlan.Take(0, 1), new ItemPlan.Take(1, 1)), "the takes");
        });
    }

    @GameTest(maxTicks = 400)
    public void aStackSplitBetweenTwoEntriesIsMetByOneTakeOfThree(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = itemProof("""
                    { "type": "item", "all_of": [ { "item": { "items": "minecraft:diamond" }, "count": 2 },
                      { "item": { "items": "minecraft:diamond" } } ] }
                    """);
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(4, new ItemStack(Items.DIAMOND, 3));
            helper.assertValueEqual(ItemProofPlanner.plan(inventory, proof).orElseThrow().takes(),
                    List.of(new ItemPlan.Take(4, 3)), "the takes");
            inventory.setItem(4, new ItemStack(Items.DIAMOND, 2));
            assertNotMet(helper, inventory, proof, "two diamonds for two entries that need three");
        });
    }

    @GameTest(maxTicks = 400)
    public void theOffhandCountsForBothKinds(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items any = itemProof("""
                    { "type": "item", "any_of": [ { "item": { "items": "minecraft:diamond" }, "count": 2 } ] }
                    """);
            Proof.Items all = itemProof("""
                    { "type": "item", "all_of": [ { "item": { "items": "minecraft:diamond" }, "count": 2 } ] }
                    """);
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.DIAMOND, 2));
            helper.assertValueEqual(ItemProofPlanner.plan(inventory, any).orElseThrow().takes(),
                    List.of(new ItemPlan.Take(Inventory.SLOT_OFFHAND, 2)), "any_of takes");
            helper.assertValueEqual(ItemProofPlanner.plan(inventory, all).orElseThrow().takes(),
                    List.of(new ItemPlan.Take(Inventory.SLOT_OFFHAND, 2)), "all_of takes");
        });
    }

    @GameTest(maxTicks = 400)
    public void armourCarriedStacksAndShulkerContentsDoNotCount(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items diamond = itemProof("""
                    { "type": "item", "any_of": [ { "item": { "items": "minecraft:diamond" } } ] }
                    """);
            Player player = mockPlayer(helper);
            Inventory inventory = player.getInventory();
            inventory.setItem(39, new ItemStack(Items.DIAMOND_HELMET));
            inventory.setItem(36, new ItemStack(Items.DIAMOND_BOOTS));
            assertNotMet(helper, inventory, diamond, "a diamond in an armour slot is not a diamond");
            player.containerMenu.setCarried(new ItemStack(Items.DIAMOND, 5));
            assertNotMet(helper, inventory, diamond, "the carried stack");
            ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
            shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 27))));
            inventory.setItem(0, shulker);
            assertNotMet(helper, inventory, diamond, "the contents of a shulker box");
        });
    }

    @GameTest(maxTicks = 400)
    public void anEntryWithoutConsumeCountsButTakesNothing(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items keepOnly = itemProof("""
                    { "type": "item", "all_of": [ { "item": { "items": "minecraft:ochre_froglight" }, "consume": false } ] }
                    """);
            Proof.Items keepAndTake = itemProof("""
                    { "type": "item", "all_of": [
                      { "item": { "items": "minecraft:ochre_froglight" }, "consume": false },
                      { "item": { "items": "minecraft:verdant_froglight" } } ] }
                    """);
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, new ItemStack(Items.OCHRE_FROGLIGHT));
            ItemPlan kept = ItemProofPlanner.plan(inventory, keepOnly).orElseThrow();
            helper.assertValueEqual(kept.takes(), List.of(), "the takes of a kept entry");
            helper.assertValueEqual(kept.apply(inventory), List.of(), "what a kept entry removes");
            helper.assertValueEqual(inventory.getItem(0).getCount(), 1, "the kept froglight");

            inventory.setItem(1, new ItemStack(Items.VERDANT_FROGLIGHT));
            helper.assertValueEqual(ItemProofPlanner.plan(inventory, keepAndTake).orElseThrow().takes(),
                    List.of(new ItemPlan.Take(1, 1)), "the takes with a taken entry");
            inventory.setItem(0, ItemStack.EMPTY);
            assertNotMet(helper, inventory, keepAndTake, "a kept entry without its own froglight");
        });
    }

    @GameTest(maxTicks = 400)
    public void anAnyOfUsesTheFirstEntryInContentOrderThatIsMet(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Proof.Items proof = itemProof("""
                    { "type": "item", "any_of": [ { "item": { "items": "minecraft:ochre_froglight" } },
                      { "item": { "items": "minecraft:verdant_froglight" } } ] }
                    """);
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, new ItemStack(Items.VERDANT_FROGLIGHT));
            inventory.setItem(5, new ItemStack(Items.OCHRE_FROGLIGHT));
            helper.assertValueEqual(ItemProofPlanner.plan(inventory, proof).orElseThrow().takes(),
                    List.of(new ItemPlan.Take(5, 1)), "the ochre, the first entry, though verdant is earlier");
            inventory.setItem(5, ItemStack.EMPTY);
            helper.assertValueEqual(ItemProofPlanner.plan(inventory, proof).orElseThrow().takes(),
                    List.of(new ItemPlan.Take(0, 1)), "the verdant, once the ochre is gone");
        });
    }

    @GameTest(maxTicks = 400)
    public void aPlanNeverChangesTheInventory(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, new ItemStack(Items.OCHRE_FROGLIGHT));
            inventory.setItem(1, new ItemStack(Items.PEARLESCENT_FROGLIGHT, 2));
            inventory.setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.DIAMOND, 3));
            List<ItemStack> before = copies(inventory);
            ItemProofPlanner.plan(inventory, itemProof("""
                    { "type": "item", "all_of": [ { "item": { "items": "minecraft:ochre_froglight" } },
                      { "item": { "items": "minecraft:pearlescent_froglight" } } ] }
                    """));
            ItemProofPlanner.plan(inventory, itemProof("""
                    { "type": "item", "any_of": [ { "item": { "items": "minecraft:diamond" }, "count": 2 } ] }
                    """));
            ItemProofPlanner.plan(inventory, itemProof("""
                    { "type": "item", "any_of": [ { "item": { "items": "minecraft:diamond" }, "count": 9 } ] }
                    """));
            helper.assertTrue(sameStacks(before, copies(inventory)), "a plan changed the inventory");
        });
    }

    @GameTest(maxTicks = 400)
    public void applyRefusesAShortSlotAndChangesNothing(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Inventory inventory = mockPlayer(helper).getInventory();
            inventory.setItem(0, new ItemStack(Items.DIAMOND, 2));
            inventory.setItem(1, new ItemStack(Items.EMERALD, 1));
            List<ItemStack> before = copies(inventory);
            ItemPlan plan = new ItemPlan(List.of(new ItemPlan.Take(0, 2), new ItemPlan.Take(1, 2)));
            try {
                plan.apply(inventory);
                helper.fail("the plan applied to a slot that holds one emerald");
            } catch (IllegalStateException expected) {
                helper.assertTrue(expected.getMessage().contains("slot 1"), "the message: " + expected.getMessage());
            }
            helper.assertTrue(sameStacks(before, copies(inventory)), "the inventory changed by a refused plan");
        });
    }

    @GameTest(maxTicks = 400)
    public void applyReturnsCopiesOfWhatItRemoved(GameTestHelper helper) {
        whenStarted(helper, () -> {
            Inventory inventory = mockPlayer(helper).getInventory();
            ItemStack held = new ItemStack(Items.DIAMOND, 5);
            inventory.setItem(0, held);
            List<ItemStack> removed = new ItemPlan(List.of(new ItemPlan.Take(0, 2))).apply(inventory);
            helper.assertValueEqual(removed.get(0).getCount(), 2, "the removed count");
            helper.assertTrue(removed.get(0) != held && removed.get(0) != inventory.getItem(0),
                    "the removed stack is the inventory's own object");
            removed.get(0).setCount(64);
            helper.assertValueEqual(inventory.getItem(0).getCount(), 3, "the inventory after changing the copy");
        });
    }

    private static List<ItemStack> copies(Inventory inventory) {
        List<ItemStack> copies = new ArrayList<>();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            copies.add(inventory.getItem(slot).copy());
        }
        copies.add(inventory.getItem(Inventory.SLOT_OFFHAND).copy());
        return copies;
    }

    private static boolean sameStacks(List<ItemStack> before, List<ItemStack> after) {
        for (int i = 0; i < before.size(); i++) {
            if (!ItemStack.matches(before.get(i), after.get(i))) {
                return false;
            }
        }
        return true;
    }
}
