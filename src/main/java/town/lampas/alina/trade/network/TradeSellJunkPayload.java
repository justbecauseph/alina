package town.lampas.alina.trade.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import town.lampas.alina.Alina;

/**
 * Client to server: sell all junk. The server re-plans the sale and refuses it when the junk now pays other than
 * {@code expectedAur}.
 *
 * @param sessionId the session the sale is made in
 * @param expectedAur the Aur the client showed for the junk
 */
public record TradeSellJunkPayload(int sessionId, int expectedAur) implements CustomPacketPayload {

    public static final Type<TradeSellJunkPayload> TYPE = new Type<>(Alina.id("trade_sell_junk"));
    public static final StreamCodec<ByteBuf, TradeSellJunkPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TradeSellJunkPayload::sessionId,
            ByteBufCodecs.VAR_INT, TradeSellJunkPayload::expectedAur,
            TradeSellJunkPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
