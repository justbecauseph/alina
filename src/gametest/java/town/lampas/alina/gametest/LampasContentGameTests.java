package town.lampas.alina.gametest;

import com.mojang.serialization.DataResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import town.lampas.alina.Alina;
import town.lampas.alina.content.AchievementsContent;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.content.Commission;
import town.lampas.alina.content.CriterionSpec;
import town.lampas.alina.content.GuildContent;
import town.lampas.alina.criteria.CommissionAcceptedTrigger;
import town.lampas.alina.criteria.CommissionCompletedTrigger;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;

/**
 * Server GameTests for the Lampas bundle, {@code content/lampas.bundle.json} in the project directory (ALINA-PLAN §6.4).
 * The bundle names Farmer's Delight items (D3) and a Gateways to Eternity criterion (F3), so it parses only where those
 * mods are loaded. Without them each test here logs that it is skipped and succeeds.
 */
public final class LampasContentGameTests {
    /** The project directory, set by {@code runGameTest} in build.gradle; the bundle is read from it. */
    private static final String PROJECT_DIR_PROPERTY = "alina.projectDir";
    private static final String FARMERS_DELIGHT = "farmersdelight";
    private static final String GATEWAYS = "gateways";

    /**
     * Every catalog commission of ALINA-PLAN §14 except E3 and F1, which need builder coordinates. The bundle must hold
     * each one, so a commission cannot leave it unnoticed.
     */
    private static final List<String> CATALOG_COMMISSIONS = List.of(
            "lampas:guild/a1_untouchable_grain", "lampas:guild/a2_bottled_shrieker",
            "lampas:guild/a3_subterranean_blossom", "lampas:guild/a4_preserved_colony",
            "lampas:guild/a5_open_water_salvage", "lampas:guild/b1_glassblowers_lightning",
            "lampas:guild/b2_froglight_triad", "lampas:guild/b3_screaming_horn", "lampas:guild/b4_withers_garden",
            "lampas:guild/b5_zombie_doctor", "lampas:guild/b6_lightning_fungus", "lampas:guild/c1_extinct_flora",
            "lampas:guild/c2_oxidized_metallurgy", "lampas:guild/d1_master_of_the_shell",
            "lampas:guild/d2_infiltration_brew", "lampas:guild/d3_grand_feast",
            "lampas:guild/e1_longshot_marksman", "lampas:guild/e2_wind_reversal",
            "lampas:guild/f2_disc_jockeys_gamble", "lampas:guild/f3_gateway_incursion", "lampas:guild/f4_heavy_core");

    /**
     * A bundle whose achievement criterion names a commission the bundle lacks ("presnt" for "present"). The readers
     * accept it, because they cannot see the commissions of alina:guild, so only the check below reports it.
     */
    private static final String MISSPELT_COMMISSION = """
            { "schema": 1,
              "dialogues": { "lampas:guild/synthetic_board": { "start": "board", "nodes": { "board": { "text": "Board." } } } },
              "extensions": {
                "alina:guild": {
                  "settings": { "board_graph": "lampas:guild/synthetic_board" },
                  "commissions": {
                    "lampas:guild/present": { "category": "A", "kind": "feat", "name": "Present", "hint": "Present.",
                      "proof": { "type": "item", "all_of": [ { "item": { "items": "minecraft:paper" } } ] } } } },
                "alina:achievements": {
                  "tabs": { "lampas:guild": { "title": "The Guild", "icon": { "id": "minecraft:paper" } } },
                  "achievements": {
                    "lampas:guild/misspelt": { "tab": "lampas:guild", "title": "Misspelt", "description": "Misspelt.",
                      "icon": { "id": "minecraft:paper" },
                      "criteria": { "deliver": { "trigger": "alina:commission_completed",
                        "conditions": { "commission": "lampas:guild/presnt" } } } },
                    "lampas:guild/spelt": { "tab": "lampas:guild", "title": "Spelt", "description": "Spelt.",
                      "icon": { "id": "minecraft:paper" },
                      "criteria": { "deliver": { "trigger": "alina:commission_completed",
                        "conditions": { "commission": "lampas:guild/present" } } } } } } } }
            """;

    /** Runs {@code body}; a failure that is not a GameTest assertion fails the test instead of stopping the server. */
    private static void check(GameTestHelper helper, Runnable body) {
        try {
            body.run();
        } catch (GameTestAssertException assertion) {
            throw assertion;
        } catch (RuntimeException failure) {
            Alina.LOGGER.error("a Lampas content check failed unexpectedly", failure);
            helper.fail("the check threw " + failure);
        }
    }

    /**
     * True when a mod the bundle needs is not loaded. The caller then returns, because this has logged the skip and
     * succeeded.
     */
    private static boolean skipWithoutMods(GameTestHelper helper, String test) {
        for (String modId : List.of(FARMERS_DELIGHT, GATEWAYS)) {
            if (!FabricLoader.getInstance().isModLoaded(modId)) {
                Alina.LOGGER.warn("{} is skipped: mod {} is not loaded", test, modId);
                helper.succeed();
                return true;
            }
        }
        return false;
    }

    private static void startupComplete(GameTestHelper helper) {
        helper.assertTrue(HaruhiServices.content().isStartupComplete(), "startup activation has not finished");
    }

    /** The Lampas bundle as the server parses it. A refusal fails the test with every problem the readers reported. */
    private static ContentBundle parseLampasBundle(GameTestHelper helper) {
        String json;
        try {
            json = Files.readString(lampasBundlePath(helper), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the Lampas bundle", e);
        }
        DataResult<ContentBundle> result = HaruhiServices.content().parseAndValidate(json);
        if (result.error().isPresent()) {
            helper.fail("the Lampas bundle was refused:\n" + result.error().get().message());
        }
        return result.result().orElseThrow();
    }

    private static Path lampasBundlePath(GameTestHelper helper) {
        String projectDir = System.getProperty(PROJECT_DIR_PROPERTY);
        if (projectDir == null) {
            helper.fail(PROJECT_DIR_PROPERTY + " is not set; runGameTest sets it to the project directory");
        }
        return Path.of(projectDir, "content", "lampas.bundle.json");
    }

    private static GuildContent guild(ContentBundle bundle) {
        return bundle.extension(AlinaContent.GUILD)
                .orElseThrow(() -> new IllegalStateException("the bundle has no alina:guild section"));
    }

    private static AchievementsContent achievements(ContentBundle bundle) {
        return bundle.extension(AlinaContent.ACHIEVEMENTS)
                .orElseThrow(() -> new IllegalStateException("the bundle has no alina:achievements section"));
    }

    /**
     * The achievement criteria that name a commission the bundle lacks, one line each. The achievement readers cannot see
     * the commissions of alina:guild, so this is the check that covers them (ALINA-PLAN §16 Q14).
     */
    private static List<String> missingCommissionReferences(AchievementsContent achievements, GuildContent guild) {
        List<String> missing = new ArrayList<>();
        for (AchievementsContent.Achievement achievement : achievements.achievements().values()) {
            for (Map.Entry<String, CriterionSpec> criterion : achievement.criteria().entrySet()) {
                Optional<Identifier> named = commissionNamedBy(criterion.getValue());
                if (named.isPresent() && !guild.commissions().containsKey(named.get())) {
                    missing.add(achievement.id() + " criterion '" + criterion.getKey() + "' names " + named.get());
                }
            }
        }
        return missing;
    }

    private static Optional<Identifier> commissionNamedBy(CriterionSpec spec) {
        if (spec.instance() instanceof CommissionCompletedTrigger.TriggerInstance completed) {
            return completed.commission();
        }
        if (spec.instance() instanceof CommissionAcceptedTrigger.TriggerInstance accepted) {
            return accepted.commission();
        }
        return Optional.empty();
    }

    @GameTest(maxTicks = 400)
    public void lampasBundleParsesWithNoProblems(GameTestHelper helper) {
        if (skipWithoutMods(helper, "Lampas bundle parse")) {
            return;
        }
        helper.succeedWhen(() -> check(helper, () -> {
            startupComplete(helper);
            parseLampasBundle(helper);
        }));
    }

    @GameTest(maxTicks = 400)
    public void lampasBundleHoldsEveryCatalogCommissionExceptE3AndF1(GameTestHelper helper) {
        if (skipWithoutMods(helper, "Lampas bundle catalog")) {
            return;
        }
        helper.succeedWhen(() -> check(helper, () -> {
            startupComplete(helper);
            GuildContent guild = guild(parseLampasBundle(helper));
            TreeSet<String> held = guild.commissions().keySet().stream().map(Identifier::toString)
                    .collect(Collectors.toCollection(TreeSet::new));
            helper.assertValueEqual(held, new TreeSet<>(CATALOG_COMMISSIONS), "the commissions of the bundle");
        }));
    }

    @GameTest(maxTicks = 400)
    public void everyLampasCommissionBelongsToExactlyOneBoard(GameTestHelper helper) {
        if (skipWithoutMods(helper, "Lampas bundle boards")) {
            return;
        }
        helper.succeedWhen(() -> check(helper, () -> {
            startupComplete(helper);
            GuildContent guild = guild(parseLampasBundle(helper));
            for (Commission commission : guild.commissions().values()) {
                long holders = guild.boards().values().stream().filter(board -> board.holds(commission)).count();
                helper.assertTrue(holders == 1,
                        commission.id() + " belongs to " + holders + " boards, not one");
            }
        }));
    }

    @GameTest(maxTicks = 400)
    public void everyLampasAchievementCriterionNamingACommissionNamesOneOfItsCommissions(GameTestHelper helper) {
        if (skipWithoutMods(helper, "Lampas bundle achievements")) {
            return;
        }
        helper.succeedWhen(() -> check(helper, () -> {
            startupComplete(helper);
            ContentBundle bundle = parseLampasBundle(helper);
            List<String> missing = missingCommissionReferences(achievements(bundle), guild(bundle));
            helper.assertTrue(missing.isEmpty(), "achievement criteria name a commission the bundle lacks:\n"
                    + String.join("\n", missing));
        }));
    }

    /** Shows that the check above reports a misspelt commission id, so an empty result means the names are right. */
    @GameTest(maxTicks = 100)
    public void aCriterionNamingAMissingCommissionIsReported(GameTestHelper helper) {
        check(helper, () -> {
            DataResult<ContentBundle> result = HaruhiServices.content().parseAndValidate(MISSPELT_COMMISSION);
            if (result.error().isPresent()) {
                helper.fail("the synthetic bundle was refused:\n" + result.error().get().message());
            }
            ContentBundle bundle = result.result().orElseThrow();
            helper.assertValueEqual(missingCommissionReferences(achievements(bundle), guild(bundle)),
                    List.of("lampas:guild/misspelt criterion 'deliver' names lampas:guild/presnt"),
                    "the criteria that name a commission the bundle lacks");
            helper.succeed();
        });
    }
}
