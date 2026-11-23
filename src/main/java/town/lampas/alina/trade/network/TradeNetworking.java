package town.lampas.alina.trade.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Registers the trade payload types, on both sides. The receivers are registered by the module that handles them, which is
 * not wired yet.
 */
public final class TradeNetworking {
    private TradeNetworking() {}

    /** Registers the four server to client payloads and the five client to server payloads. */
    public static void registerPayloadTypes() {
        PayloadTypeRegistry.clientboundPlay().register(TradeOpenPayload.TYPE, TradeOpenPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TradeViewPayload.TYPE, TradeViewPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TradeResultPayload.TYPE, TradeResultPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TradeClosePayload.TYPE, TradeClosePayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TradeSellPayload.TYPE, TradeSellPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TradeSellJunkPayload.TYPE, TradeSellJunkPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TradeBuyPayload.TYPE, TradeBuyPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TradeExperiencePayload.TYPE, TradeExperiencePayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TradeDismissPayload.TYPE, TradeDismissPayload.STREAM_CODEC);
    }
}
