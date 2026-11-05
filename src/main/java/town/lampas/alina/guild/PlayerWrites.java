package town.lampas.alina.guild;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The storage calls of each character, in order (ALINA-PLAN §8.1). A call starts when the character's previous call has
 * completed, whether it succeeded or failed, so a load never reads past a write made before it. Calls are made on the
 * server thread; the futures complete on storage threads.
 */
final class PlayerWrites {
    /** The last call of each character, completed or not. Completed entries are pruned. */
    private final Map<UUID, CompletableFuture<?>> tails = new HashMap<>();

    /**
     * Starts {@code call} once the character's earlier calls have completed. The returned future completes with the call's
     * result or failure. A call that throws when it starts fails its future.
     */
    <T> CompletableFuture<T> submit(UUID character, Supplier<CompletableFuture<T>> call) {
        CompletableFuture<?> previous = tails.get(character);
        CompletableFuture<T> result = previous == null || previous.isDone()
                ? start(call)
                : previous.handle((ignored, failure) -> null).thenCompose(ignored -> start(call));
        tails.put(character, result.handle((ignored, failure) -> null));
        return result;
    }

    /** Forgets the calls that have all completed. Server thread. */
    void prune() {
        tails.values().removeIf(CompletableFuture::isDone);
    }

    private static <T> CompletableFuture<T> start(Supplier<CompletableFuture<T>> call) {
        try {
            return call.get();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }
}
