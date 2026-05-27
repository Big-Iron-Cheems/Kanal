package io.github.bigironcheems.kanal.examples;

import io.github.bigironcheems.kanal.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Demonstrates async dispatch from Java.
 * <p>
 * Async dispatch runs individual handlers on a configured {@link java.util.concurrent.Executor}
 * while preserving priority ordering, mutation visibility, and cancellation.
 * If no executor is configured, {@code async = true} handlers fall back to sync silently.
 */
public class AsyncExample {

    // Event definitions

    static class PacketEvent implements Event {
        final byte[] bytes;

        PacketEvent(byte[] bytes) {
            this.bytes = bytes;
        }
    }

    static class DamageEvent implements Event, Modifiable<Double> {
        private Double value;

        DamageEvent(double value) { this.value = value; }

        @Override
        public Double getValue() { return value; }

        @Override
        public void setValue(Double v) { value = v; }
    }

    static class NetworkRequestEvent implements Event, Cancellable {
        final String url;
        private boolean cancelled = false;

        NetworkRequestEvent(String url) { this.url = url; }

        @Override
        public boolean isCancelled() { return cancelled; }

        @Override
        public void setCancelled(boolean v) { cancelled = v; }
    }

    // Annotation-based async subscriber

    static class AsyncNetworkListener {
        @Subscribe(priority = Priority.HIGH, async = true)
        public void onPacket(PacketEvent e) {
            IO.println("[high async] packet on " + Thread.currentThread().getName());
        }

        // Runs after the HIGH async handler completes — sees its mutations.
        @Subscribe(priority = Priority.LOW)
        public void onPacketLow(PacketEvent e) {
            IO.println("[low sync] runs after high async finishes");
        }
    }

    // 1. Basic postAsync — fire and forget vs awaiting completion

    static void basicPostAsync() {
        EventBus bus = EventBus.create(Executors.newVirtualThreadPerTaskExecutor());
        bus.subscribe(PacketEvent.class, Priority.NORMAL, true,
                e -> IO.println("[async] received " + e.bytes.length + " bytes on "
                        + Thread.currentThread().getName()));

        // Fire and forget — handler runs on a virtual thread.
        CompletableFuture<PacketEvent> future = bus.postAsync(new PacketEvent(new byte[128]));

        // Chain follow-up work without blocking.
        future.thenAccept(e -> IO.println("All handlers done for " + e.bytes.length + "-byte packet"));
        future.join(); // wait for this example to complete
    }

    // 2. Annotation-based async handler

    static void annotationAsyncHandler() {
        EventBus bus = EventBus.create(Executors.newVirtualThreadPerTaskExecutor());
        bus.subscribe(new AsyncNetworkListener());
        bus.postAsync(new PacketEvent(new byte[0])).join();
    }

    // 3. Blocking post with async handlers
    //
    // post() blocks until all handlers (sync and async) finish.
    // Safe with virtual-thread executors; can deadlock on bounded platform pools
    // if a handler re-entrantly posts on the same pool.

    static void blockingPostWithAsyncHandlers() {
        EventBus bus = EventBus.create(Executors.newVirtualThreadPerTaskExecutor());
        bus.subscribe(PacketEvent.class, Priority.NORMAL, true,
                e -> IO.println("handled " + e.bytes.length + " bytes"));

        bus.post(new PacketEvent(new byte[64]));
        IO.println("post returned after all handlers finished");
    }

    // 4. Mixed sync + async — priority and mutation visibility
    //
    // A lower-priority sync handler always observes mutations from higher-priority
    // async handlers; the chain drains before each sync step executes.

    static void mixedSyncAsync() {
        EventBus bus = EventBus.create(Executors.newVirtualThreadPerTaskExecutor());

        bus.subscribe(DamageEvent.class, Priority.HIGH, true, e -> {
            e.setValue(e.getValue() * 2.0);
            IO.println("[HIGH async] doubled to " + e.getValue());
        });
        bus.subscribe(DamageEvent.class, Priority.LOW, e -> {
            // Runs after HIGH async completes — sees the doubled value.
            IO.println("[LOW sync] final value: " + e.getValue());
        });

        bus.postAsync(new DamageEvent(5.0)).join();
        // Output:
        //   [HIGH async] doubled to 10.0
        //   [LOW sync] final value: 10.0
    }

    // 5. Cancellation under async dispatch
    //
    // Cancellation is automatically thread-safe. The bus wraps the isCancelled
    // field in an AtomicBoolean for the duration of the chain and writes the
    // result back to the original event once all handlers complete.

    static void asyncCancellation() {
        EventBus bus = EventBus.create(Executors.newVirtualThreadPerTaskExecutor());

        bus.subscribe(NetworkRequestEvent.class, Priority.HIGH, true, e -> {
            IO.println("Validating " + e.url + ": blocking request");
            e.cancel(); // subsequent handlers will not run
        });
        bus.subscribe(NetworkRequestEvent.class, Priority.LOW, true,
                e -> IO.println("Should not reach here"));

        NetworkRequestEvent event =
                bus.postAsync(new NetworkRequestEvent("https://example.com")).join();
        IO.println("Cancelled: " + event.isCancelled()); // true
    }

    // 6. Fallback to sync when no executor is configured
    //
    // async=true handlers run synchronously on the calling thread when the bus
    // has no executor. postAsync returns an already-completed future.

    static void asyncFallbackToSync() {
        EventBus bus = EventBus.create(); // no executor
        bus.subscribe(PacketEvent.class, Priority.NORMAL, true,
                e -> IO.println("Fallback sync on " + Thread.currentThread().getName()));

        CompletableFuture<PacketEvent> future = bus.postAsync(new PacketEvent(new byte[0]));
        IO.println("Future already done: " + future.isDone()); // true
        future.join();
    }

    // 7. Custom error handler with async dispatch
    //
    // Exceptions from handlers are routed to the error handler, not propagated
    // through the CompletableFuture.

    static void asyncErrorHandling() {
        EventBus bus = EventBus.createWithHandler(
                Executors.newVirtualThreadPerTaskExecutor(),
                t -> IO.println("Handler error: " + t.getMessage())
        );
        bus.subscribe(PacketEvent.class, Priority.NORMAL, true,
                e -> { throw new RuntimeException("boom"); });

        bus.postAsync(new PacketEvent(new byte[0])).join(); // does not throw
        IO.println("Dispatch completed despite handler error");
    }

    public static void main(String[] args) {
        IO.println("=== 1. Basic postAsync ===");
        basicPostAsync();
        IO.println("\n=== 2. Annotation async handler ===");
        annotationAsyncHandler();
        IO.println("\n=== 3. Blocking post with async handlers ===");
        blockingPostWithAsyncHandlers();
        IO.println("\n=== 4. Mixed sync + async ===");
        mixedSyncAsync();
        IO.println("\n=== 5. Async cancellation ===");
        asyncCancellation();
        IO.println("\n=== 6. Fallback to sync ===");
        asyncFallbackToSync();
        IO.println("\n=== 7. Error handling ===");
        asyncErrorHandling();
    }
}

