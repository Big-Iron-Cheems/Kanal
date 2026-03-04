package io.github.bigironcheems.kanal.examples

import io.github.bigironcheems.kanal.*
import java.util.concurrent.Executors

// ---------------------------------------------------------------------------
// Async dispatch examples
//
// Async dispatch runs individual handlers on a configured Executor while
// preserving priority ordering, mutation visibility, and cancellation.
// If no executor is configured, async=true handlers fall back to sync silently.
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 1. Basic postAsync — fire and forget vs awaiting completion
// ---------------------------------------------------------------------------

fun basicPostAsync() {
    val bus = EventBus(Executors.newVirtualThreadPerTaskExecutor())
    bus.subscribe<PacketEvent>(async = true) { e ->
        println("[async] received ${e.bytes.size} bytes on ${Thread.currentThread().name}")
    }

    // Fire and forget — returns immediately; handler runs on a virtual thread.
    val future = bus.postAsync(PacketEvent(ByteArray(128)))

    // Await completion to ensure the handler has finished before continuing.
    future.join()
    println("All handlers done")
}

// ---------------------------------------------------------------------------
// 2. @Subscribe(async = true) — annotation-based async handler
// ---------------------------------------------------------------------------

class NetworkListener {
    @Subscribe(priority = Priority.HIGH, async = true)
    fun onPacket(e: PacketEvent) {
        println("[high async] packet on ${Thread.currentThread().name}")
    }

    // This sync handler runs after the async one completes, observing any
    // mutations the async handler made to the event.
    @Subscribe(priority = Priority.LOW)
    fun onPacketLow(e: PacketEvent) {
        println("[low sync] packet: runs after high async finishes")
    }
}

fun annotationAsyncHandler() {
    val bus = EventBus(Executors.newVirtualThreadPerTaskExecutor())
    bus.subscribe(NetworkListener())
    bus.postAsync(PacketEvent(ByteArray(0))).join()
}

// ---------------------------------------------------------------------------
// 3. Blocking post with async handlers
//
// post() blocks the calling thread until all handlers (sync and async) finish.
// Semantically identical to postAsync().join(), but convenient when the caller
// already owns the thread and does not want a CompletableFuture.
// Safe with virtual-thread executors; can deadlock on bounded platform pools
// if a handler re-entrantly posts on the same pool.
// ---------------------------------------------------------------------------

fun blockingPostWithAsyncHandlers() {
    val bus = EventBus(Executors.newVirtualThreadPerTaskExecutor())
    bus.subscribe<PacketEvent>(async = true) { e ->
        println("handled ${e.bytes.size} bytes")
    }

    // Returns only after all handlers finish — same guarantee as plain post().
    val event = bus.post(PacketEvent(ByteArray(64)))
    println("post returned: $event")
}

// ---------------------------------------------------------------------------
// 4. Mixed sync + async handlers — priority and mutation visibility
//
// The bus chains handlers in priority order. A lower-priority sync handler
// waits for all higher-priority async handlers to complete before running,
// so it always sees their mutations.
// ---------------------------------------------------------------------------

fun mixedSyncAsync() {
    val bus = EventBus(Executors.newVirtualThreadPerTaskExecutor())

    bus.subscribe<DamageEvent>(Priority.HIGH, async = true) { e ->
        e.value *= 2.0   // async: double the damage
        println("[HIGH async] doubled to ${e.value}")
    }
    bus.subscribe<DamageEvent>(Priority.LOW) { e ->
        // Runs after HIGH async completes — sees the doubled value.
        println("[LOW sync] final value: ${e.value}")
    }

    bus.postAsync(DamageEvent(5.0)).join()
    // Output:
    //   [HIGH async] doubled to 10.0
    //   [LOW sync] final value: 10.0
}

// ---------------------------------------------------------------------------
// 5. Cancellation under async dispatch
//
// Cancellation is automatically thread-safe. The bus wraps the isCancelled
// field in an AtomicBoolean for the duration of the chain and writes the
// result back to the original event once all handlers complete.
// ---------------------------------------------------------------------------

fun asyncCancellation() {
    val bus = EventBus(Executors.newVirtualThreadPerTaskExecutor())

    bus.subscribe<NetworkRequestEvent>(Priority.HIGH, async = true) { e ->
        println("Validating ${e.url} — blocking request")
        e.isCancelled = true   // cancel: subsequent handlers will not run
    }
    bus.subscribe<NetworkRequestEvent>(Priority.LOW, async = true) { e ->
        // This must not run.
        println("Should not reach here")
    }

    val event = bus.postAsync(NetworkRequestEvent("https://example.com")).join()
    println("Cancelled: ${event.isCancelled}")   // true
}

// ---------------------------------------------------------------------------
// 6. Fallback to sync when no executor is configured
//
// async=true handlers run synchronously on the calling thread when the bus
// has no executor. No exception is thrown; postAsync returns an already-
// completed future.
// ---------------------------------------------------------------------------

fun asyncFallbackToSync() {
    val bus = EventBus()   // no executor
    bus.subscribe<PacketEvent>(async = true) { e ->
        println("Fallback sync on ${Thread.currentThread().name}")
    }

    val future = bus.postAsync(PacketEvent(ByteArray(0)))
    println("Future already done: ${future.isDone}")   // true
    future.join()
}

// ---------------------------------------------------------------------------
// 7. Custom error handler with async dispatch
//
// Exceptions thrown by handlers are routed to the error handler, not
// propagated through the CompletableFuture. The future always completes
// normally (unless the executor itself rejects the task).
// ---------------------------------------------------------------------------

fun asyncErrorHandling() {
    val bus = EventBus(
        asyncExecutor = Executors.newVirtualThreadPerTaskExecutor(),
        exceptionHandler = { t -> println("Handler error: ${t.message}") }
    )
    bus.subscribe<PacketEvent>(async = true) { throw RuntimeException("boom") }

    bus.postAsync(PacketEvent(ByteArray(0))).join()   // does not throw
    println("Dispatch completed despite handler error")
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

fun main() {
    println("=== 1. Basic postAsync ===")
    basicPostAsync()

    println("\n=== 2. Annotation async handler ===")
    annotationAsyncHandler()

    println("\n=== 3. Blocking post with async handlers ===")
    blockingPostWithAsyncHandlers()

    println("\n=== 4. Mixed sync + async ===")
    mixedSyncAsync()

    println("\n=== 5. Async cancellation ===")
    asyncCancellation()

    println("\n=== 6. Fallback to sync ===")
    asyncFallbackToSync()

    println("\n=== 7. Error handling ===")
    asyncErrorHandling()
}

