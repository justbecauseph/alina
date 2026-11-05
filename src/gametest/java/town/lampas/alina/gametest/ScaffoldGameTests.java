package town.lampas.alina.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import town.lampas.haruhi.api.HaruhiServices;

/** Alina and haruhi load together on one server, and haruhi's services are reachable from Alina's classpath. */
public final class ScaffoldGameTests {

    @GameTest
    public void alinaAndHaruhiLoadTogether(GameTestHelper helper) {
        helper.assertTrue(FabricLoader.getInstance().isModLoaded("alina"), "alina is not loaded");
        helper.assertTrue(FabricLoader.getInstance().isModLoaded("haruhi"), "haruhi is not loaded");
        helper.assertTrue(HaruhiServices.content().active() != null, "haruhi's content service has no active bundle");
        helper.succeed();
    }
}
