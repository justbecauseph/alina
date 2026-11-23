package town.lampas.alina.trade.network;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import town.lampas.alina.Alina;

/**
 * Server to client: the outcome of the player's last request, with the line the shop says about it.
 *
 * @param sessionId the session the result belongs to
 * @param accepted whether the server carried the request out
 * @param line what the shop says
 */
public record TradeResultPayload(int sessionId, boolean accepted, Component line) implements CustomPacketPayload {

    public static final Type<TradeResultPayload> TYPE = new Type<>(Alina.id("trade_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TradeResultPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TradeResultPayload::sessionId,
            ByteBufCodecs.BOOL, TradeResultPayload::accepted,
            ComponentSerialization.STREAM_CODEC, TradeResultPayload::line,
            TradeResultPayload::new);

    public TradeResultPayload {
        Objects.requireNonNull(line, "line");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
