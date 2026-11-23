package town.lampas.alina.trade.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import town.lampas.alina.Alina;

/**
 * Client to server: sell {@code count} items of the main inventory stack in {@code slot}. The server re-checks the sale, and
 * refuses it when the trade now pays other than {@code expectedAur} for it.
 *
 * @param sessionId the session the sale is made in
 * @param slot the main inventory slot
 * @param count the items to sell
 * @param expectedAur the Aur the client showed for the sale
 */
public record TradeSellPayload(int sessionId, int slot, int count, int expectedAur) implements CustomPacketPayload {

    public static final Type<TradeSellPayload> TYPE = new Type<>(Alina.id("trade_sell"));
    public static final StreamCodec<ByteBuf, TradeSellPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TradeSellPayload::sessionId,
            ByteBufCodecs.VAR_INT, TradeSellPayload::slot,
            ByteBufCodecs.VAR_INT, TradeSellPayload::count,
            ByteBufCodecs.VAR_INT, TradeSellPayload::expectedAur,
            TradeSellPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
