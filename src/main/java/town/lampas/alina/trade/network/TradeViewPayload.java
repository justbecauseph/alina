package town.lampas.alina.trade.network;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import town.lampas.alina.Alina;

/**
 * Server to client: the trade as it stands after a change, for the open session.
 *
 * @param sessionId the session the view belongs to
 * @param view the trade as it stands now
 */
public record TradeViewPayload(int sessionId, TradeView view) implements CustomPacketPayload {

    public static final Type<TradeViewPayload> TYPE = new Type<>(Alina.id("trade_view"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TradeViewPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TradeViewPayload::sessionId,
            TradeView.STREAM_CODEC, TradeViewPayload::view,
            TradeViewPayload::new);

    public TradeViewPayload {
        Objects.requireNonNull(view, "view");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
