package town.lampas.alina.trade;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import town.lampas.alina.trade.network.TradeClosePayload;
import town.lampas.alina.trade.network.TradeOpenPayload;
import town.lampas.alina.trade.network.TradeResultPayload;
import town.lampas.alina.trade.network.TradeViewPayload;

/**
 * The trade's messenger over Fabric's play networking. Nothing is sent to a player whose connection has gone, so a
 * session that closes for a player who left does not throw.
 */
public final class NetworkMessenger implements TradeMessenger {

    @Override
    public boolean canOpen(ServerPlayer player) {
        return player.connection != null && ServerPlayNetworking.canSend(player, TradeOpenPayload.TYPE);
    }

    @Override
    public void open(ServerPlayer player, TradeOpenPayload payload) {
        send(player, payload);
    }

    @Override
    public void view(ServerPlayer player, TradeViewPayload payload) {
        send(player, payload);
    }

    @Override
    public void result(ServerPlayer player, TradeResultPayload payload) {
        send(player, payload);
    }

    @Override
    public void close(ServerPlayer player, TradeClosePayload payload) {
        send(player, payload);
    }

    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection != null && !player.hasDisconnected()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
