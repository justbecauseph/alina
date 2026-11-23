package town.lampas.alina.client;

import net.fabricmc.api.ClientModInitializer;
import town.lampas.alina.client.trade.TradeClient;

/**
 * Client entrypoint. Alina's achievements use the vanilla advancement screen; the trade screen is opened by
 * {@link TradeClient}, which registers its receivers here.
 */
public final class AlinaClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TradeClient.init();
    }
}
