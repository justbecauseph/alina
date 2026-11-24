package town.lampas.alina.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.jsonrpc.IncomingRpcMethod;
import net.minecraft.server.jsonrpc.api.Schema;
import net.minecraft.server.jsonrpc.methods.DiscoveryService;
import town.lampas.alina.trade.remote.TradeRpc;

/**
 * Server GameTests for the read-only {@code alina:trade/*} Management Protocol RPCs.
 */
public final class TradeRpcGameTests {

    @GameTest(maxTicks = 40)
    public void tradeManagementRpcsAreRegisteredOffMainThreadAndAnswerOk(GameTestHelper helper) {
        for (Identifier id : TradeRpc.ALL) {
            helper.assertTrue(BuiltInRegistries.INCOMING_RPC_METHOD.containsKey(id), id + " is not registered");
            IncomingRpcMethod<?, ?> method = BuiltInRegistries.INCOMING_RPC_METHOD.getValue(id);
            helper.assertTrue(method != null, id + " resolved to null");
            helper.assertTrue(!method.attributes().runOnMainThread(), id + " must run off the main thread");
            helper.assertTrue(method.attributes().discoverable(), id + " must be discoverable");
        }

        DataResult<JsonElement> discovered = DiscoveryService.DiscoverResponse.CODEC.codec()
                .encodeStart(JsonOps.INSTANCE, DiscoveryService.discover(Schema.getSchemaRegistry()));
        helper.assertTrue(discovered.error().isEmpty(), "rpc.discover does not encode: "
                + discovered.error().map(DataResult.Error::message).orElse(""));
        String discoveryJson = discovered.result().orElseThrow().toString();
        for (Identifier id : TradeRpc.ALL) {
            helper.assertTrue(discoveryJson.contains(id.toString()), "rpc.discover does not list " + id);
        }

        // Invoke the registered handlers from a worker thread (matching .notOnMainThread()) while the server ticks.
        AtomicReference<CompletableFuture<JsonObject>> marketCall = new AtomicReference<>();
        AtomicReference<CompletableFuture<JsonObject>> summaryCall = new AtomicReference<>();
        AtomicReference<CompletableFuture<JsonObject>> operationsCall = new AtomicReference<>();

        helper.startSequence()
                .thenExecute(() -> {
                    marketCall.set(CompletableFuture.supplyAsync(() -> invoke(TradeRpc.MARKET)));
                    summaryCall.set(CompletableFuture.supplyAsync(() -> invoke(TradeRpc.SUMMARY)));
                    operationsCall.set(CompletableFuture.supplyAsync(() -> invoke(TradeRpc.OPERATIONS)));
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(marketCall.get().isDone()
                            && summaryCall.get().isDone()
                            && operationsCall.get().isDone(),
                            "trade RPC calls have not completed yet");
                    assertOk(helper, TradeRpc.MARKET, marketCall.get().join());
                    assertOk(helper, TradeRpc.SUMMARY, summaryCall.get().join());
                    assertOk(helper, TradeRpc.OPERATIONS, operationsCall.get().join());
                })
                .thenSucceed();
    }

    private static JsonObject invoke(Identifier id) {
        IncomingRpcMethod<?, ?> method = BuiltInRegistries.INCOMING_RPC_METHOD.getValue(id);
        JsonObject envelope = new JsonObject();
        envelope.add("params", new JsonObject());
        return method.apply(null, envelope, null).getAsJsonObject();
    }

    private static void assertOk(GameTestHelper helper, Identifier id, JsonObject answer) {
        helper.assertValueEqual(answer.get("status").getAsString(), "ok", id + " answer " + answer);
    }
}
