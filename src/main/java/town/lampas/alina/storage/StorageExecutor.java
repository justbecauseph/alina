package town.lampas.alina.storage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The threads a storage backend runs its work on. Every future {@link #submit} returns completes: with the work's
 * result or failure, or with an {@link IllegalStateException} when the work never ran because {@link #close()} dropped
 * it or it was submitted after close. A caller, and any chain built on its future, therefore never waits on work that
 * will not run.
 *
 * <p>Every future completes off the caller's thread, as {@link AlinaStorage} requires. Work completes its future on a
 * storage thread. Work that never ran is failed on a separate thread, so neither {@link #close()}, which the server
 * thread calls when the server stops, nor a late {@link #submit} runs the future's dependents itself.
 */
final class StorageExecutor {
    private static final String NOT_RUN = "Alina storage closed before this operation ran";

    private final ExecutorService threads;
    private final Duration grace;
    private final Executor abandonments;

    /**
     * @param threads the number of threads, so the number of operations that run at once
     * @param threadNamePrefix the prefix of each thread's name
     * @param grace how long {@link #close()} lets queued work run before it drops the rest
     */
    StorageExecutor(int threads, String threadNamePrefix, Duration grace) {
        this(threads, threadNamePrefix, grace, newThreadPerTask(threadNamePrefix + "closed-"));
    }

    /**
     * @param abandonments where the futures of work that never ran are failed. It must not run a task on the thread
     *     that hands it over.
     */
    StorageExecutor(int threads, String threadNamePrefix, Duration grace, Executor abandonments) {
        this.threads = Executors.newFixedThreadPool(
                threads, Thread.ofPlatform().name(threadNamePrefix, 0).daemon(true).factory());
        this.grace = grace;
        this.abandonments = abandonments;
    }

    /** Runs the work on a storage thread. A failure, including one from a closed storage, comes through the future. */
    <T> CompletableFuture<T> submit(Supplier<T> work) {
        Task<T> task = new Task<>(work);
        try {
            threads.execute(task);
        } catch (RejectedExecutionException closed) {
            abandon(List.of(task));
        }
        return task.future;
    }

    /**
     * Stops accepting work and waits up to the grace period for queued work to run. Then it interrupts the running
     * work and fails the queued work that has not started.
     */
    void close() {
        threads.shutdown();
        boolean interrupted = false;
        boolean finished;
        try {
            finished = threads.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            finished = false;
            interrupted = true;
        }
        if (!finished) {
            List<Task<?>> dropped = new ArrayList<>();
            for (Runnable task : threads.shutdownNow()) {
                if (task instanceof Task<?> queued) {
                    dropped.add(queued);
                }
            }
            if (!dropped.isEmpty()) {
                abandon(dropped);
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Fails the futures of work that will never run, off the calling thread. */
    private void abandon(List<Task<?>> tasks) {
        Runnable fail = () -> tasks.forEach(Task::fail);
        try {
            abandonments.execute(fail);
        } catch (RejectedExecutionException refused) {
            // A future that completes here is better than one that never completes.
            fail.run();
        }
    }

    /** Starts one daemon thread for each task, so nothing needs shutting down. Used only once work cannot run. */
    private static Executor newThreadPerTask(String threadNamePrefix) {
        ThreadFactory factory = Thread.ofPlatform().name(threadNamePrefix, 0).daemon(true).factory();
        return task -> factory.newThread(task).start();
    }

    /** One unit of work and the future its caller holds. */
    private static final class Task<T> implements Runnable {
        private final Supplier<T> work;
        private final CompletableFuture<T> future = new CompletableFuture<>();

        Task(Supplier<T> work) {
            this.work = work;
        }

        @Override
        public void run() {
            try {
                future.complete(work.get());
            } catch (Throwable failure) {
                // Wrapped as CompletableFuture.supplyAsync wraps it, so callers see the same exception types.
                future.completeExceptionally(
                        failure instanceof CompletionException ? failure : new CompletionException(failure));
            }
        }

        void fail() {
            future.completeExceptionally(new CompletionException(new IllegalStateException(NOT_RUN)));
        }
    }
}
