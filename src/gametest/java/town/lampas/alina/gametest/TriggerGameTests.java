package town.lampas.alina.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.CriterionTriggerInstance;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import town.lampas.alina.Alina;
import town.lampas.alina.content.CriterionSpec;
import town.lampas.alina.criteria.AlinaTriggers;
import town.lampas.alina.criteria.CriterionWatchers;

/**
 * Alina's six criterion triggers (ALINA-PLAN §6.3) through the watchers. Each test opens two watches on one trigger,
 * one whose conditions match the fired event and one whose conditions do not, fires the trigger through its fire
 * method and checks which watch counted it.
 */
@SuppressWarnings("removal")
public final class TriggerGameTests {

    /** A spec for {@code trigger} with the conditions parsed as content parses them. */
    private static CriterionSpec spec(GameTestHelper helper, String trigger, String conditions) {
        Identifier id = Identifier.parse(trigger);
        CriterionTrigger<?> type = BuiltInRegistries.TRIGGER_TYPES.getValue(id);
        RegistryOps<JsonElement> ops =
                RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().getServer().registryAccess());
        CriterionTriggerInstance instance = type.codec().parse(ops, JsonParser.parseString(conditions))
                .getOrThrow(message -> new IllegalStateException("conditions do not parse: " + message));
        return new CriterionSpec(id, instance, 1);
    }

    /** A watch for the player that adds 1 to {@code counter} for each event its conditions match. */
    private static CriterionWatchers.Watch watch(GameTestHelper helper, ServerPlayer player, String trigger,
            String conditions, AtomicInteger counter) {
        return CriterionWatchers.watch(player.getUUID(), spec(helper, trigger, conditions),
                fired -> counter.incrementAndGet());
    }

    @GameTest(maxTicks = 40)
    public void commissionAcceptedCountsOnlyTheAcceptancesItsConditionsName(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        AtomicInteger horn = new AtomicInteger();
        AtomicInteger feats = new AtomicInteger();
        CriterionWatchers.Watch hornWatch = watch(helper, player, "alina:commission_accepted", """
                { "commission": "alina:guild/b3_screaming_horn", "category": "B", "kind": "repeatable" }
                """, horn);
        CriterionWatchers.Watch featWatch = watch(helper, player, "alina:commission_accepted",
                "{ \"kind\": \"feat\" }", feats);

        AlinaTriggers.COMMISSION_ACCEPTED.trigger(player, Alina.id("guild/b3_screaming_horn"), 'B', "repeatable");
        helper.assertValueEqual(horn.get(), 1, "the horn acceptance the horn watch matches");
        helper.assertValueEqual(feats.get(), 0, "a repeatable acceptance is not a feat");

        AlinaTriggers.COMMISSION_ACCEPTED.trigger(player, Alina.id("guild/e1_longshot"), 'E', "feat");
        helper.assertValueEqual(horn.get(), 1, "another commission is not the horn");
        helper.assertValueEqual(feats.get(), 1, "the feat acceptance the feat watch matches");

        hornWatch.close();
        featWatch.close();
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void commissionCompletedCountsOnlyTheTurnInsItsConditionsName(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        AtomicInteger eCommissions = new AtomicInteger();
        AtomicInteger repeatables = new AtomicInteger();
        CriterionWatchers.Watch eWatch = watch(helper, player, "alina:commission_completed",
                "{ \"category\": \"E\" }", eCommissions);
        CriterionWatchers.Watch repeatableWatch = watch(helper, player, "alina:commission_completed",
                "{ \"kind\": \"repeatable\" }", repeatables);

        AlinaTriggers.COMMISSION_COMPLETED.trigger(player, Alina.id("guild/e1_longshot"), 'E', "feat");
        helper.assertValueEqual(eCommissions.get(), 1, "the category E turn-in");
        helper.assertValueEqual(repeatables.get(), 0, "a feat turn-in is not repeatable");

        AlinaTriggers.COMMISSION_COMPLETED.trigger(player, Alina.id("guild/b3_screaming_horn"), 'B', "repeatable");
        helper.assertValueEqual(eCommissions.get(), 1, "a category B turn-in is not category E");
        helper.assertValueEqual(repeatables.get(), 1, "the repeatable turn-in");

        eWatch.close();
        repeatableWatch.close();
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void reputationChangedCountsOnlyTheValuesItsBoundsContain(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        AtomicInteger trusted = new AtomicInteger();
        AtomicInteger wary = new AtomicInteger();
        CriterionWatchers.Watch trustedWatch = watch(helper, player, "alina:reputation_changed",
                "{ \"reputation\": { \"min\": 10 } }", trusted);
        CriterionWatchers.Watch waryWatch = watch(helper, player, "alina:reputation_changed",
                "{ \"reputation\": { \"max\": 5 } }", wary);

        AlinaTriggers.REPUTATION_CHANGED.trigger(player, 12);
        helper.assertValueEqual(trusted.get(), 1, "a reputation of 12 is at least 10");
        helper.assertValueEqual(wary.get(), 0, "a reputation of 12 is not at most 5");

        AlinaTriggers.REPUTATION_CHANGED.trigger(player, 3);
        helper.assertValueEqual(trusted.get(), 1, "a reputation of 3 is not at least 10");
        helper.assertValueEqual(wary.get(), 1, "a reputation of 3 is at most 5");

        trustedWatch.close();
        waryWatch.close();
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void volumeEnteredCountsOnlyTheVolumeItsConditionNames(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        AtomicInteger highDive = new AtomicInteger();
        AtomicInteger diveBasin = new AtomicInteger();
        CriterionWatchers.Watch highWatch = watch(helper, player, "alina:volume_entered",
                "{ \"volume\": \"alina:guild/high_dive\" }", highDive);
        CriterionWatchers.Watch basinWatch = watch(helper, player, "alina:volume_entered",
                "{ \"volume\": \"alina:guild/dive_basin\" }", diveBasin);

        AlinaTriggers.VOLUME_ENTERED.trigger(player, Alina.id("guild/high_dive"));
        helper.assertValueEqual(highDive.get(), 1, "the high dive the high dive watch names");
        helper.assertValueEqual(diveBasin.get(), 0, "the high dive is not the basin");

        AlinaTriggers.VOLUME_ENTERED.trigger(player, Alina.id("guild/dive_basin"));
        helper.assertValueEqual(highDive.get(), 1, "the basin is not the high dive");
        helper.assertValueEqual(diveBasin.get(), 1, "the dive basin the basin watch names");

        highWatch.close();
        basinWatch.close();
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void achievementEarnedCountsOnlyTheTiersItsConditionsReach(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        AtomicInteger firstAtTier2 = new AtomicInteger();
        AtomicInteger slayer = new AtomicInteger();
        CriterionWatchers.Watch firstWatch = watch(helper, player, "alina:achievement_earned",
                "{ \"achievement\": \"alina:guild/first_commission\", \"tier\": { \"min\": 2 } }", firstAtTier2);
        CriterionWatchers.Watch slayerWatch = watch(helper, player, "alina:achievement_earned",
                "{ \"achievement\": \"alina:guild/zombie_slayer\" }", slayer);

        AlinaTriggers.ACHIEVEMENT_EARNED.trigger(player, Alina.id("guild/first_commission"), 1);
        helper.assertValueEqual(firstAtTier2.get(), 0, "tier 1 is below the tier bound of 2");
        helper.assertValueEqual(slayer.get(), 0, "the first commission is not the zombie slayer");

        AlinaTriggers.ACHIEVEMENT_EARNED.trigger(player, Alina.id("guild/first_commission"), 2);
        helper.assertValueEqual(firstAtTier2.get(), 1, "tier 2 of the first commission");

        AlinaTriggers.ACHIEVEMENT_EARNED.trigger(player, Alina.id("guild/zombie_slayer"), 3);
        helper.assertValueEqual(firstAtTier2.get(), 1, "the zombie slayer is not the first commission");
        helper.assertValueEqual(slayer.get(), 1, "the zombie slayer the slayer watch names");

        firstWatch.close();
        slayerWatch.close();
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void manualCountsOnlyTheKeyItsConditionNames(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        AtomicInteger met = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        CriterionWatchers.Watch metWatch = watch(helper, player, "alina:manual",
                "{ \"key\": \"met_receptionist\" }", met);
        CriterionWatchers.Watch otherWatch = watch(helper, player, "alina:manual",
                "{ \"key\": \"other\" }", other);

        AlinaTriggers.MANUAL.trigger(player, "met_receptionist");
        helper.assertValueEqual(met.get(), 1, "the key the watch names");
        helper.assertValueEqual(other.get(), 0, "a different key");

        metWatch.close();
        otherWatch.close();
        helper.succeed();
    }

    /**
     * Each trigger is in {@code TRIGGER_TYPES} under its alina id, and a datapack advancement's {@code trigger} field
     * resolves to it through {@code CriteriaTriggers.CODEC}, the codec vanilla reads that field with.
     */
    @GameTest(maxTicks = 20)
    public void eachTriggerIsRegisteredUnderItsAlinaId(GameTestHelper helper) {
        Map<String, CriterionTrigger<?>> triggers = Map.of(
                "commission_accepted", AlinaTriggers.COMMISSION_ACCEPTED,
                "commission_completed", AlinaTriggers.COMMISSION_COMPLETED,
                "reputation_changed", AlinaTriggers.REPUTATION_CHANGED,
                "volume_entered", AlinaTriggers.VOLUME_ENTERED,
                "achievement_earned", AlinaTriggers.ACHIEVEMENT_EARNED,
                "manual", AlinaTriggers.MANUAL);
        for (Map.Entry<String, CriterionTrigger<?>> entry : triggers.entrySet()) {
            Identifier id = Alina.id(entry.getKey());
            CriterionTrigger<?> trigger = entry.getValue();
            helper.assertTrue(BuiltInRegistries.TRIGGER_TYPES.getValue(id) == trigger,
                    id + " is not registered as its trigger");
            CriterionTrigger<?> fromDatapack = CriteriaTriggers.CODEC
                    .parse(JsonOps.INSTANCE, new JsonPrimitive(id.toString()))
                    .getOrThrow(message -> new IllegalStateException(id + " does not resolve: " + message));
            helper.assertTrue(fromDatapack == trigger, id + " does not resolve to its trigger from a datapack");
        }
        helper.succeed();
    }
}
