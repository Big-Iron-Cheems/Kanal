package io.github.bigironcheems.kanal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the Java-facing async API surface.
 * <p>
 * Complements {@link AsyncDispatchTest} (Kotlin) by exercising Java call sites specifically:
 * {@code EventBus.create(executor)}, {@code Consumer<T>} subscribe with {@code async=true},
 * {@code postAsync} as a {@link CompletableFuture} from Java, exception routing, and
 * the no-executor fallback path.
 */
class AsyncJavaInteropTest {

    // Event fixtures

    static class SimpleEvent implements Event {
    }

    static class CancellableAsyncEvent implements Event, Cancellable {
        // Deliberately NOT volatile: the bus must handle async-safe cancellation automatically.
        private boolean cancelled = false;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean v) {
            cancelled = v;
        }
    }

    // 1. EventBus.create(executor) factory overload

    @Test
    void createWithExecutor_returnsWorkingAsyncBus() throws Exception {
        EventBus bus = EventBus.create(Executors.newVirtualThreadPerTaskExecutor());
        AtomicBoolean called = new AtomicBoolean(false);
        bus.subscribe(SimpleEvent.class, Priority.NORMAL, true, e -> called.set(true));
        bus.postAsync(new SimpleEvent()).get(5, TimeUnit.SECONDS);
        assertTrue(called.get());
    }

    @Test
    void createWithNullExecutor_fallsBackToSync() {
        EventBus bus = EventBus.create(null);
        AtomicBoolean called = new AtomicBoolean(false);
        bus.subscribe(SimpleEvent.class, Priority.NORMAL, true, e -> called.set(true));
        CompletableFuture<SimpleEvent> future = bus.postAsync(new SimpleEvent());
        // With no executor the future is already completed (ran synchronously).
        assertTrue(future.isDone(), "Expected future to be already done on no-executor bus");
        assertTrue(called.get());
    }

    // 2. Consumer subscribe with async = true fires off calling thread

    @Test
    void consumerSubscribeAsyncTrue_firesOffCallingThread() throws Exception {
        EventBus bus = EventBus.create(Executors.newVirtualThreadPerTaskExecutor());
        AtomicReference<Thread> handlerThread = new AtomicReference<>();

        bus.subscribe(SimpleEvent.class, Priority.NORMAL, true,
            e -> handlerThread.set(Thread.currentThread()));

        bus.postAsync(new SimpleEvent()).get(5, TimeUnit.SECONDS);
        assertNotNull(handlerThread.get());
        assertNotSame(Thread.currentThread(), handlerThread.get(),
            "Async handler must run off the calling thread");
    }

    // 3. postAsync returns CompletableFuture; thenAccept fires with event instance

    @Test
    void postAsync_completesWithEventInstance() throws Exception {
        EventBus bus = EventBus.create(Executors.newVirtualThreadPerTaskExecutor());
        SimpleEvent event = new SimpleEvent();
        CompletableFuture<SimpleEvent> future = bus.postAsync(event);
        SimpleEvent returned = future.get(5, TimeUnit.SECONDS);
        assertSame(event, returned, "postAsync must complete with the original event instance");
    }

    @Test
    void postAsync_thenAccept_firesAfterAllHandlers() throws Exception {
        EventBus bus = EventBus.create(Executors.newVirtualThreadPerTaskExecutor());
        AtomicBoolean handlerRan = new AtomicBoolean(false);
        AtomicBoolean thenAcceptRan = new AtomicBoolean(false);

        bus.subscribe(SimpleEvent.class, Priority.NORMAL, true, e -> handlerRan.set(true));

        CompletableFuture<Void> downstream = bus.postAsync(new SimpleEvent())
            .thenAccept(e -> thenAcceptRan.set(true));
        downstream.get(5, TimeUnit.SECONDS);

        assertTrue(handlerRan.get(), "Handler must have run before thenAccept");
        assertTrue(thenAcceptRan.get(), "thenAccept must fire after all handlers complete");
    }

    // 4. postAsync never completes exceptionally on handler throw

    @Test
    void postAsync_doesNotCompleteExceptionallyOnHandlerThrow() throws Exception {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        EventBus bus = EventBus.createWithHandler(
            Executors.newVirtualThreadPerTaskExecutor(),
            caught::set
        );

        bus.subscribe(SimpleEvent.class, Priority.NORMAL, true, e -> {
            throw new RuntimeException("handler error");
        });

        // Must not throw; exception must be routed to exceptionHandler only.
        SimpleEvent result = bus.postAsync(new SimpleEvent()).get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertNotNull(caught.get(), "Exception must be routed to exceptionHandler");
        assertEquals("handler error", caught.get().getMessage());
    }

    // 5. Async fallback to sync when no executor is configured

    @Test
    void asyncFallbackToSync_handlerRunsOnCallingThread() {
        EventBus bus = EventBus.create(null); // no executor
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        Thread callingThread = Thread.currentThread();

        bus.subscribe(SimpleEvent.class, Priority.NORMAL, true,
            e -> handlerThread.set(Thread.currentThread()));

        bus.post(new SimpleEvent());

        assertNotNull(handlerThread.get());
        assertSame(callingThread, handlerThread.get(),
            "With no executor, async = true handler must run on the calling thread");
    }

    // 6. Cancellation flushed back to original event after postAsync

    @Test
    void postAsync_cancellationFlushedBackToOriginalEvent() throws Exception {
        EventBus bus = EventBus.create(Executors.newVirtualThreadPerTaskExecutor());

        bus.subscribe(CancellableAsyncEvent.class, Priority.HIGH, true,
            e -> e.cancel());

        CancellableAsyncEvent event = new CancellableAsyncEvent();
        CancellableAsyncEvent returned = bus.postAsync(event).get(5, TimeUnit.SECONDS);

        assertSame(event, returned);
        assertTrue(returned.isCancelled(),
            "isCancelled must be flushed back to the original event after postAsync completes");
    }
}

