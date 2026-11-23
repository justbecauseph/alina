package town.lampas.alina.trade.network;

import io.netty.buffer.ByteBuf;
import java.util.Objects;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import town.lampas.alina.Alina;

/**
 * Client to server: buy {@code count} items of an offer. The server re-checks the purchase, and refuses it when the offer
 * now costs other than {@code expectedCost}.
 *
 * @param sessionId the session the purchase is made in
 * @param offerId the offer's id, at most {@link OfferRow#MAX_ID_LENGTH} characters
 * @param count the items to buy
 * @param expectedCost the Aur the client showed for the purchase
 */
public record TradeBuyPayload(int sessionId, String offerId, int count, int expectedCost) implements CustomPacketPayload {

    public static final Type<TradeBuyPayload> TYPE = new Type<>(Alina.id("trade_buy"));
    public static final StreamCodec<ByteBuf, TradeBuyPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TradeBuyPayload::sessionId,
            ByteBufCodecs.stringUtf8(OfferRow.MAX_ID_LENGTH), TradeBuyPayload::offerId,
            ByteBufCodecs.VAR_INT, TradeBuyPayload::count,
            ByteBufCodecs.VAR_INT, TradeBuyPayload::expectedCost,
            TradeBuyPayload::new);

    public TradeBuyPayload {
        Objects.requireNonNull(offerId, "offerId");
        if (offerId.length() > OfferRow.MAX_ID_LENGTH) {
            throw new IllegalArgumentException("an offer id is at most " + OfferRow.MAX_ID_LENGTH + " characters");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
