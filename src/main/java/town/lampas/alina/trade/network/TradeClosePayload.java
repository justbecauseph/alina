package town.lampas.alina.trade.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import town.lampas.alina.Alina;

/**
 * Server to client: the trade is over; the client closes its screen if the session is the one open.
 *
 * @param sessionId the session that ends
 */
public record TradeClosePayload(int sessionId) implements CustomPacketPayload {

    public static final Type<TradeClosePayload> TYPE = new Type<>(Alina.id("trade_close"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TradeClosePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TradeClosePayload::sessionId,
            TradeClosePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
