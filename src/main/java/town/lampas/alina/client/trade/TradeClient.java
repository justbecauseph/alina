package town.lampas.alina.client.trade;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import town.lampas.alina.trade.network.TradeClosePayload;
import town.lampas.alina.trade.network.TradeOpenPayload;
import town.lampas.alina.trade.network.TradeResultPayload;
import town.lampas.alina.trade.network.TradeViewPayload;

/**
 * Receives the clientbound trade payloads. {@link TradeOpenPayload} opens a {@link TradeScreen}, replacing whatever screen
 * is open. The view, result and close payloads reach the open screen only when their session id is that screen's; a stale
 * one is ignored. The payload types must be registered on both sides before {@link #init()} runs, which the server's trade
 * networking does in common init.
 */
public final class TradeClient {
    private TradeClient() {}

    /** Registers the four clientbound receivers. Called once from the client entrypoint. */
    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(TradeOpenPayload.TYPE,
                (payload, context) -> context.client().execute(() -> open(payload)));
        ClientPlayNetworking.registerGlobalReceiver(TradeViewPayload.TYPE,
                (payload, context) -> context.client().execute(() -> showView(payload)));
        ClientPlayNetworking.registerGlobalReceiver(TradeResultPayload.TYPE,
                (payload, context) -> context.client().execute(() -> showResult(payload)));
        ClientPlayNetworking.registerGlobalReceiver(TradeClosePayload.TYPE,
                (payload, context) -> context.client().execute(() -> close(payload)));
    }

    private static void open(TradeOpenPayload payload) {
        Minecraft.getInstance().gui.setScreen(new TradeScreen(payload));
    }

    private static void showView(TradeViewPayload payload) {
        TradeScreen screen = openScreen(payload.sessionId());
        if (screen != null) {
            screen.showView(payload.view());
        }
    }

    private static void showResult(TradeResultPayload payload) {
        TradeScreen screen = openScreen(payload.sessionId());
        if (screen != null) {
            screen.showResult(payload.line());
        }
    }

    /** Closes the screen without a dismiss: the server ended the session, so there is nothing to tell it. */
    private static void close(TradeClosePayload payload) {
        if (openScreen(payload.sessionId()) != null) {
            Minecraft.getInstance().gui.setScreen(null);
        }
    }

    /** The open screen when it is the trade for {@code sessionId}, else null. */
    private static TradeScreen openScreen(int sessionId) {
        return Minecraft.getInstance().gui.screen() instanceof TradeScreen screen && screen.sessionId() == sessionId
                ? screen
                : null;
    }
}
