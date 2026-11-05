package town.lampas.alina;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import town.lampas.alina.content.AlinaContent;
import town.lampas.alina.criteria.AlinaTriggers;
import town.lampas.alina.criteria.CriteriaModule;
import town.lampas.alina.settlement.SettlementModule;
import town.lampas.alina.storage.StorageModule;

/**
 * Common entrypoint. The modules initialise in dependency order (ALINA-PLAN §5): storage, criteria, content,
 * settlement, guild, achievement. The criterion trigger types register right after storage, before content parses
 * criteria. haruhi's services are read from server events onward, never here.
 */
public final class Alina implements ModInitializer {
    public static final String MOD_ID = "alina";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        StorageModule.init();
        AlinaTriggers.init();
        CriteriaModule.init();
        AlinaContent.init();
        SettlementModule.init();
    }
}
