package town.lampas.alina.gametest;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.advancements.triggers.Criterion;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import town.lampas.alina.content.CriterionSpec;
import town.lampas.alina.criteria.CriterionWatchers;

/**
 * Spike S2 (ALINA-PLAN §12): the watchers see a trigger's events after the player has finished every vanilla
 * advancement that uses that trigger, when vanilla itself returns before testing anything. Each test first completes
 * those advancements for its player and checks that vanilla has no listener left for the trigger, then makes the event
 * happen through the game where it can: a real kill, a real cure through the golden-apple conversion, and the fishing
 * trigger called as the fishing hook calls it.
 */
@SuppressWarnings("removal")
public final class CriteriaGameTests {

    /** Awards every criterion of every advancement that uses {@code trigger}, so vanilla stops listening to it. */
    private static void finishEveryAdvancementUsing(GameTestHelper helper, ServerPlayer player, CriterionTrigger<?> trigger) {
        int finished = 0;
        for (AdvancementHolder holder : helper.getLevel().getServer().getAdvancements().getAllAdvancements()) {
            Map<String, Criterion<?>> criteria = holder.value().criteria();
            if (criteria.values().stream().anyMatch(criterion -> criterion.trigger() == trigger)) {
                criteria.keySet().forEach(name -> player.getAdvancements().award(holder, name));
                finished++;
            }
        }
        helper.assertTrue(finished > 0, "no vanilla advancement uses the trigger, so the test proves nothing");
        Map<?, ?> listening = player.getAdvancements().getTriggerMapForType(trigger);
        helper.assertTrue(listening == null || listening.isEmpty(),
                "vanilla still listens to the trigger for this player: " + listening);
    }

    /** A spec for {@code trigger} with vanilla condition JSON, parsed as content parses it. */
    private static CriterionSpec spec(GameTestHelper helper, String trigger, String conditions) {
        Identifier id = Identifier.parse(trigger);
        CriterionTrigger<?> type = BuiltInRegistries.TRIGGER_TYPES.getValue(id);
        RegistryOps<com.google.gson.JsonElement> ops =
                RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().getServer().registryAccess());
        CriterionTriggerInstance instance = type.codec().parse(ops, JsonParser.parseString(conditions))
                .getOrThrow(message -> new IllegalStateException("conditions do not parse: " + message));
        return new CriterionSpec(id, instance, 1);
    }

    @GameTest(maxTicks = 100)
    public void aKillIsSeenAfterEveryKillAdvancementIsDone(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        finishEveryAdvancementUsing(helper, player, CriteriaTriggers.PLAYER_KILLED_ENTITY);
        AtomicInteger skeletons = new AtomicInteger();
        AtomicInteger zombies = new AtomicInteger();
        CriterionWatchers.Watch skeletonWatch = CriterionWatchers.watch(player.getUUID(), spec(helper,
                "minecraft:player_killed_entity", """
                        { "entity": [ { "condition": "minecraft:entity_properties", "entity": "this",
                                        "predicate": { "minecraft:entity_type": "minecraft:skeleton" } } ] }
                        """), killer -> skeletons.incrementAndGet());
        CriterionWatchers.Watch zombieWatch = CriterionWatchers.watch(player.getUUID(), spec(helper,
                "minecraft:player_killed_entity", """
                        { "entity": [ { "condition": "minecraft:entity_properties", "entity": "this",
                                        "predicate": { "minecraft:entity_type": "minecraft:zombie" } } ] }
                        """), killer -> zombies.incrementAndGet());

        Skeleton first = helper.spawn(EntityTypes.SKELETON, new BlockPos(1, 2, 1));
        first.hurtServer(helper.getLevel(), player.damageSources().playerAttack(player), 1000.0F);
        helper.assertValueEqual(skeletons.get(), 1, "skeleton kills the watcher saw");
        helper.assertValueEqual(zombies.get(), 0, "the zombie watcher does not count a skeleton");

        skeletonWatch.close();
        Skeleton second = helper.spawn(EntityTypes.SKELETON, new BlockPos(2, 2, 1));
        second.hurtServer(helper.getLevel(), player.damageSources().playerAttack(player), 1000.0F);
        helper.assertValueEqual(skeletons.get(), 1, "a closed watch sees nothing more");
        zombieWatch.close();
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void aCureIsSeenAfterEveryCureAdvancementIsDone(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        finishEveryAdvancementUsing(helper, player, CriteriaTriggers.CURED_ZOMBIE_VILLAGER);
        AtomicInteger cures = new AtomicInteger();
        CriterionWatchers.Watch watch = CriterionWatchers.watch(player.getUUID(),
                spec(helper, "minecraft:cured_zombie_villager", "{}"), curer -> cures.incrementAndGet());

        ZombieVillager zombie = helper.spawn(EntityTypes.ZOMBIE_VILLAGER, new BlockPos(1, 2, 1));
        zombie.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 600));
        zombie.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 600));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_APPLE));
        zombie.interact(player, InteractionHand.MAIN_HAND, zombie.position());
        helper.assertTrue(zombie.isConverting(), "the golden apple started the cure");
        // The cure normally takes minutes; the conversion it finishes is the vanilla one.
        zombie.setVillagerConversionTime(1);
        helper.succeedWhen(() -> {
            helper.assertValueEqual(cures.get(), 1, "cures the watcher saw");
            watch.close();
        });
    }

    @GameTest(maxTicks = 40)
    public void aCatchIsSeenAfterEveryFishingAdvancementIsDone(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        finishEveryAdvancementUsing(helper, player, CriteriaTriggers.FISHING_ROD_HOOKED);
        AtomicInteger nameTags = new AtomicInteger();
        CriterionWatchers.Watch watch = CriterionWatchers.watch(player.getUUID(),
                spec(helper, "minecraft:fishing_rod_hooked", "{ \"item\": { \"items\": \"minecraft:name_tag\" } }"),
                fisher -> nameTags.incrementAndGet());

        ItemStack rod = new ItemStack(Items.FISHING_ROD);
        FishingHook hook = new FishingHook(player, helper.getLevel(), 0, 0);
        // FishingHook.retrieve calls the trigger like this when it reels a catch in.
        CriteriaTriggers.FISHING_ROD_HOOKED.trigger(player, rod, hook, List.of(new ItemStack(Items.COD)));
        helper.assertValueEqual(nameTags.get(), 0, "a cod does not match the name tag condition");
        CriteriaTriggers.FISHING_ROD_HOOKED.trigger(player, rod, hook, List.of(new ItemStack(Items.NAME_TAG)));
        helper.assertValueEqual(nameTags.get(), 1, "name tag catches the watcher saw");
        hook.discard();
        watch.close();
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void anotherPlayersEventsAreNotSeen(GameTestHelper helper) {
        ServerPlayer watched = helper.makeMockServerPlayerInLevel();
        ServerPlayer other = helper.makeMockServerPlayerInLevel();
        AtomicInteger catches = new AtomicInteger();
        CriterionWatchers.Watch watch = CriterionWatchers.watch(watched.getUUID(),
                spec(helper, "minecraft:fishing_rod_hooked", "{}"), fisher -> catches.incrementAndGet());
        FishingHook hook = new FishingHook(other, helper.getLevel(), 0, 0);
        CriteriaTriggers.FISHING_ROD_HOOKED.trigger(other, new ItemStack(Items.FISHING_ROD), hook,
                List.of(new ItemStack(Items.COD)));
        helper.assertValueEqual(catches.get(), 0, "another player's catch");
        hook.discard();
        watch.close();
        helper.succeed();
    }
}
