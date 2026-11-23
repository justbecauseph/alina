package town.lampas.alina.gametest;

import com.mojang.serialization.DataResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import town.lampas.alina.Alina;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.content.GuildContent;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;

/**
 * Server GameTests owned by the content component: a bundle with both Alina extensions validates with the server's
 * registries and loot tables and becomes active, and a broken one is refused with every problem listed.
 */
public final class ContentGameTests {
    private static final String FIXTURES = "/alina/fixtures/content/";
    private static final String TRADE_FIXTURES = "/alina/fixtures/trade/";

    private static String fixture(String name) {
        return resource(FIXTURES + name);
    }

    private static String resource(String path) {
        try (InputStream in = ContentGameTests.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GameTest(maxTicks = 400)
    public void bundleWithBothExtensionsActivates(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            helper.assertTrue(HaruhiServices.content().isStartupComplete(), "startup activation has not finished");
            DataResult<ContentBundle> result = HaruhiServices.content().parseAndValidate(fixture("both.json"));
            helper.assertTrue(result.error().isEmpty(),
                    "the fixture was refused:\n" + result.error().map(error -> error.message()).orElse(""));
            ContentBundle bundle = result.result().orElseThrow();
            HaruhiServices.content().activateUnstored(bundle);
            GuildContent guild = AlinaContent.guild();
            helper.assertTrue(guild.commissions().containsKey(Alina.id("test/horn")), "the commission is not active");
            helper.assertTrue(AlinaContent.achievements().achievements().containsKey(Alina.id("test/slayer")),
                    "the achievement is not active");
        });
    }

    @GameTest(maxTicks = 400)
    public void brokenBundleListsEveryProblem(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            helper.assertTrue(HaruhiServices.content().isStartupComplete(), "startup activation has not finished");
            DataResult<ContentBundle> result = HaruhiServices.content().parseAndValidate(fixture("broken.json"));
            String message = result.error().map(error -> error.message()).orElse("");
            helper.assertTrue(message.contains("loot_table alina:rewards/missing does not exist on this server"),
                    "no loot table problem in:\n" + message);
            helper.assertTrue(message.contains("tag #alina:not_a_tag is not an instrument tag on this server"),
                    "no tag problem in:\n" + message);
            helper.assertTrue(message.contains("tab 'alina:nowhere' is not a tab in this extension"),
                    "no tab problem in:\n" + message);
            helper.assertTrue(message.contains("dimension minecraft:overwold is not a dimension on this server"),
                    "no dimension problem in:\n" + message);
            helper.assertTrue(message.contains("commission alina:test/ghost, proof: conditions: ")
                            && message.contains("alina:no_such_predicate"),
                    "no criterion validation problem in:\n" + message);
            helper.assertTrue(message.contains("'entity[0].predicate.minecraft:distance.horizontl' has no effect"),
                    "no misspelt-key problem in:\n" + message);
        });
    }

    @GameTest(maxTicks = 400)
    public void brokenTradeBundleListsEveryProblem(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            helper.assertTrue(HaruhiServices.content().isStartupComplete(), "startup activation has not finished");
            DataResult<ContentBundle> result = HaruhiServices.content().parseAndValidate(resource(TRADE_FIXTURES + "broken.json"));
            String message = result.error().map(error -> error.message()).orElse("");
            String[] expected = {
                    "currency entry 2: item 'minecraft:gold_ingot' is already the item of entry 1",
                    "currency entry 3: value 9 is already the value of entry 1",
                    "currency entry 4: item 'minecraft:not_a_real_item' is not a registered item",
                    "currency needs a denomination of value 1",
                    "price list alina:trade_broken/list, rule 1: namespace 'no_such_mod_xyz' has no registered items on this server",
                    "shop alina:trade_broken/mint_shop: experience buy costs 2 aur for 50 points",
                    "shop alina:trade_broken/mint_shop, offer paper_offer: shop alina:trade_broken/mint_shop pays 10 aur for 1 item",
                    "shop alina:trade_broken/bad_shop, sell: lists entry 1 names price list 'alina:trade_broken/missing_list'",
                    "shop alina:trade_broken/bad_shop, buy entry 2: id 'dup' is already the id of buy entry 1",
                    "shop alina:trade_broken/bad_shop, offer coin_offer: item is a currency item, so the offer would sell Aur",
                    "shop alina:trade_broken/no_rules, sell: has no rules",
                    "shop alina:trade_broken/xp_no_sell: experience sell needs sell with a daily_budget",
                    "alina:trade_broken/talk#ask: alina:trade/open: 'shop' names 'alina:trade_broken/ghost'",
                    "alina:trade_broken/talk#ask: alina:trade/open: 'then' names 'alina:trade_broken/nowhere'",
                    "alina:trade_broken/talk#ask: alina:trade/budget_left: 'shop' names 'alina:trade_broken/ghost'"
            };
            for (String needle : expected) {
                helper.assertTrue(message.contains(needle), "missing '" + needle + "' in:\n" + message);
            }
        });
    }
}
