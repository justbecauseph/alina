package town.lampas.alina.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The executor's promises: every future it returns completes, a future whose work never ran fails with an
 * {@link IllegalStateException}, and no future completes on the thread that calls {@code submit} or {@code close}.
 * Waits use {@link CompletableFuture#get(long, TimeUnit)}, whose timeout is a {@code TimeoutException} and not an
 * {@code ExecutionException}, so a future that never completes fails these tests instead of passing them.
 */
class StorageExecutorTest {
    private static final long WAIT_SECONDS = 10;
    private static final Duration SHORT_GRACE = Duration.ofMillis(100);

    @Test
    void aQueuedTaskThatCloseDropsFailsOffTheClosingThread() throws Exception {
        StorageExecutor executor = new StorageExecutor(1, "test-storage-", SHORT_GRACE);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> busy = executor.submit(() -> {
            running.countDown();
            return awaitRelease(release);
        });
        assertTrue(running.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first task never started");
        // The only thread is busy, so this task waits in the queue until close drops it.
        CompletableFuture<String> queued = executor.submit(() -> "ran");
        CompletableFuture<Thread> queuedCompletedOn = queued.handle((value, failure) -> Thread.currentThread());

        executor.close();

        assertInstanceOf(IllegalStateException.class, failureOf(queued));
        assertNotSame(Thread.currentThread(), queuedCompletedOn.get(WAIT_SECONDS, TimeUnit.SECONDS),
                "a dropped task's future completed on the thread that closed the executor");
        assertInstanceOf(IllegalStateException.class, failureOf(busy));
    }

    @Test
    void workSubmittedAfterCloseFailsItsFutureWithoutThrowing() throws Exception {
        StorageExecutor executor = new StorageExecutor(1, "test-storage-", SHORT_GRACE);
        executor.close();

        CompletableFuture<String> late = assertDoesNotThrow(() -> executor.submit(() -> "ran"));

        assertInstanceOf(IllegalStateException.class, failureOf(late));
    }

    @Test
    void workSubmittedAfterCloseIsNotFailedOnTheSubmittingThread() throws Exception {
        List<Runnable> handedOver = new CopyOnWriteArrayList<>();
        Executor holding = handedOver::add;
        StorageExecutor executor = new StorageExecutor(1, "test-storage-", SHORT_GRACE, holding);
        executor.close();

        CompletableFuture<String> late = executor.submit(() -> "ran");

        assertFalse(late.isDone(), "the late future completed on the submitting thread");
        assertEquals(1, handedOver.size(), "the late future was not handed over to be failed");
        CompletableFuture<Thread> completedOn = late.handle((value, failure) -> Thread.currentThread());
        Thread failing = Thread.ofPlatform().start(handedOver.get(0));
        failing.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertInstanceOf(IllegalStateException.class, failureOf(late));
        assertEquals(failing, completedOn.get(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void workQueuedBeforeCloseRunsAndCompletesWithItsResult() throws Exception {
        StorageExecutor executor = new StorageExecutor(1, "test-storage-", Duration.ofSeconds(WAIT_SECONDS));
        CompletableFuture<String> first = executor.submit(() -> "first");
        CompletableFuture<String> second = executor.submit(() -> "second");

        executor.close();

        assertEquals("first", first.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals("second", second.get(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    /** The cause a future failed with. A future still pending after {@link #WAIT_SECONDS} fails the test. */
    private static Throwable failureOf(CompletableFuture<?> future) {
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> future.get(WAIT_SECONDS, TimeUnit.SECONDS),
                "the future must fail within " + WAIT_SECONDS + " seconds");
        return thrown.getCause();
    }

    /** Waits for the release latch, and turns an interrupt from close into a failure of the work. */
    private static String awaitRelease(CountDownLatch release) {
        try {
            release.await();
            return "released";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted by close", interrupted);
        }
    }
}
