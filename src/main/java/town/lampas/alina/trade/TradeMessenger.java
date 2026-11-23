package town.lampas.alina.trade;

import net.minecraft.server.level.ServerPlayer;
import town.lampas.alina.trade.network.TradeClosePayload;
import town.lampas.alina.trade.network.TradeOpenPayload;
import town.lampas.alina.trade.network.TradeResultPayload;
import town.lampas.alina.trade.network.TradeViewPayload;

/**
 * Sends the trade's server-to-client payloads. {@link TradeService} calls it on the server thread. The network
 * implementation is {@link NetworkMessenger}; tests record the calls instead.
 */
public interface TradeMessenger {

    /** Whether the player's client has the Alina mod and so can receive the trade screen. */
    boolean canOpen(ServerPlayer player);

    void open(ServerPlayer player, TradeOpenPayload payload);

    void view(ServerPlayer player, TradeViewPayload payload);

    void result(ServerPlayer player, TradeResultPayload payload);

    void close(ServerPlayer player, TradeClosePayload payload);
}
