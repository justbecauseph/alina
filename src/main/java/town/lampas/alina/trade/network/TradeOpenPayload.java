package town.lampas.alina.trade.network;

import java.util.Objects;
import java.util.OptionalInt;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import town.lampas.alina.Alina;

/**
 * Server to client: open the trade screen for a shop.
 *
 * @param sessionId the server's session id; the client echoes it so a stale screen is ignored
 * @param shopId the shop's id in the content
 * @param shopName the shop's name as the content gives it
 * @param speakerEntityId the entity to show as the shopkeeper, when it is loaded on the client
 * @param line the greeting the shop opens with
 * @param view the trade as it stands now
 */
public record TradeOpenPayload(
        int sessionId,
        Identifier shopId,
        Component shopName,
        OptionalInt speakerEntityId,
        Component line,
        TradeView view) implements CustomPacketPayload {

    public static final Type<TradeOpenPayload> TYPE = new Type<>(Alina.id("trade_open"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TradeOpenPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TradeOpenPayload::sessionId,
            Identifier.STREAM_CODEC, TradeOpenPayload::shopId,
            ComponentSerialization.STREAM_CODEC, TradeOpenPayload::shopName,
            ByteBufCodecs.OPTIONAL_VAR_INT, TradeOpenPayload::speakerEntityId,
            ComponentSerialization.STREAM_CODEC, TradeOpenPayload::line,
            TradeView.STREAM_CODEC, TradeOpenPayload::view,
            TradeOpenPayload::new);

    public TradeOpenPayload {
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(shopName, "shopName");
        Objects.requireNonNull(speakerEntityId, "speakerEntityId");
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(view, "view");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
