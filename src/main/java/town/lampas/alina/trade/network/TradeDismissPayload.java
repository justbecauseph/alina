package town.lampas.alina.trade.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import town.lampas.alina.Alina;

/**
 * Client to server: the player closed the trade screen. The server ends the session if it is the one open.
 *
 * @param sessionId the session the player closed
 */
public record TradeDismissPayload(int sessionId) implements CustomPacketPayload {

    public static final Type<TradeDismissPayload> TYPE = new Type<>(Alina.id("trade_dismiss"));
    public static final StreamCodec<ByteBuf, TradeDismissPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TradeDismissPayload::sessionId,
            TradeDismissPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
