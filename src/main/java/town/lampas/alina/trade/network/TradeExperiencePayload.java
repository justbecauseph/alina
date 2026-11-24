package town.lampas.alina.trade.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import town.lampas.alina.Alina;

/**
 * Client to server: sell experience for {@code aur} Aur, or buy experience for {@code aur} Aur. The server re-checks the
 * trade. At a fixed rate it refuses the trade when the points it would move are not {@code expectedPoints}. At a shop that
 * trades against the experience market, {@code expectedPoints} is the quote: the server accepts it when the live points differ
 * from the quote by no more than the market's quote tolerance, or in the player's favour, and then moves the live points.
 *
 * @param sessionId the session the trade is made in
 * @param selling true to sell the player's experience to the shop, false to buy it from the shop
 * @param aur the Aur the trade pays or costs
 * @param expectedPoints the experience points the client showed for the trade, which at a market shop is the quote
 */
public record TradeExperiencePayload(int sessionId, boolean selling, int aur, int expectedPoints)
        implements CustomPacketPayload {

    public static final Type<TradeExperiencePayload> TYPE = new Type<>(Alina.id("trade_experience"));
    public static final StreamCodec<ByteBuf, TradeExperiencePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TradeExperiencePayload::sessionId,
            ByteBufCodecs.BOOL, TradeExperiencePayload::selling,
            ByteBufCodecs.VAR_INT, TradeExperiencePayload::aur,
            ByteBufCodecs.VAR_INT, TradeExperiencePayload::expectedPoints,
            TradeExperiencePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
