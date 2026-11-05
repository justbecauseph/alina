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

    private static String fixture(String name) {
        try (InputStream in = ContentGameTests.class.getResourceAsStream(FIXTURES + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
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
}
