package io.github.bigironcheems.kanal.coroutines.examples

import io.github.bigironcheems.kanal.*
import io.github.bigironcheems.kanal.coroutines.SuspendHandlerBehaviour
import io.github.bigironcheems.kanal.coroutines.suspendHandler
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Demonstrates [suspendHandler] — registering a suspend lambda handler on an [EventBus].
 *
 * Unlike [io.github.bigironcheems.kanal.coroutines.asFlow], [suspendHandler] registers
 * its subscription synchronously before returning, so there is no subscription timing
 * race. Events can be posted immediately after registration.
 *
 * Always provide an explicit [CoroutineScope] in production code to control the lifecycle
 * of handler coroutines. The default internal scope is convenient for examples but creates
 * an unmanaged scope with no structured cancellation guarantee.
 */

// 1. Parallel — new coroutine per event, all run concurrently

fun handlerParallelBehaviour() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val bus = EventBus()
    val received = mutableListOf<String>()

    val sub = bus.suspendHandler<PlayerJumpEvent>(
        scope = scope,
        behaviour = SuspendHandlerBehaviour.Parallel
    ) { e ->
        delay(10.milliseconds)
        synchronized(received) { received += e.player }
    }

    bus.post(PlayerJumpEvent("Steve"))
    bus.post(PlayerJumpEvent("Alex"))
    bus.post(PlayerJumpEvent("Sam"))

    delay(100.milliseconds) // wait for all parallel coroutines to complete
    println("Received: $received")
    sub.cancel()
}

// 2. DiscardIfBusy — skip new events while handler is still running

fun handlerDiscardIfBusyBehaviour() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val bus = EventBus()
    var handledCount = 0

    val sub = bus.suspendHandler<PlayerJumpEvent>(
        scope = scope,
        behaviour = SuspendHandlerBehaviour.DiscardIfBusy
    ) { e ->
        delay(200.milliseconds) // slow handler
        handledCount++
        println("Handled: ${e.player}")
    }

    // Post 3 events rapidly — only first is handled, rest discarded while handler runs
    bus.post(PlayerJumpEvent("Steve"))  // handled
    bus.post(PlayerJumpEvent("Alex"))   // discarded — first still running
    bus.post(PlayerJumpEvent("Sam"))    // discarded — first still running

    delay(300.milliseconds)
    println("Total handled: $handledCount") // 1
    sub.cancel()
}

// 3. ReplaceLatest — cancel previous coroutine, launch new one for latest event

fun handlerReplaceLatestBehaviour() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val bus = EventBus()
    val completed = mutableListOf<String>()

    val sub = bus.suspendHandler<PlayerJumpEvent>(
        scope = scope,
        behaviour = SuspendHandlerBehaviour.ReplaceLatest
    ) { e ->
        delay(200.milliseconds) // each handler takes 200ms
        synchronized(completed) { completed += e.player }
        println("Completed: ${e.player}")
    }

    bus.post(PlayerJumpEvent("Steve"))  // starts, then cancelled
    delay(50.milliseconds)
    bus.post(PlayerJumpEvent("Alex"))   // cancels Steve, starts, then cancelled
    delay(50.milliseconds)
    bus.post(PlayerJumpEvent("Sam"))    // cancels Alex, runs to completion

    delay(400.milliseconds)
    println("Completed: $completed") // [Sam]
    sub.cancel()
}

// 4. Priority — note: suspendHandler launches a coroutine asynchronously,
// so the order depends on when the coroutine scheduler runs the handler.
// The dispatch priority controls when the coroutine is *launched*, not when
// it *completes*. For deterministic ordering use a sync @Subscribe handler instead.
fun handlerWithPriority() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val bus = EventBus()
    val order = mutableListOf<String>()

    bus.subscribe<PlayerJumpEvent>(Priority.NORMAL) { order += "sync-normal" }

    val sub = bus.suspendHandler<PlayerJumpEvent>(
        priority = Priority.HIGH,
        scope = scope
    ) { order += "suspend-high" }

    bus.post(PlayerJumpEvent("Steve"))
    delay(100.milliseconds)
    println("Order: $order") // [sync-normal, suspend-high] — sync fires during post(),
    // suspend-high fires when coroutine scheduler runs
    sub.cancel()
}

// 5. Subscription cancellation — handler unregistered on cancel

fun handlerSubscriptionCancellation() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val bus = EventBus()
    var count = 0

    val sub = bus.suspendHandler<PlayerJumpEvent>(scope = scope) { count++ }

    bus.post(PlayerJumpEvent("Steve"))
    delay(50.milliseconds)
    println("Count before cancel: $count") // 1

    sub.cancel()
    bus.post(PlayerJumpEvent("Alex"))
    delay(50.milliseconds)
    println("Count after cancel: $count")  // still 1
    println("Listening: ${bus.isListening<PlayerJumpEvent>()}") // false
}

// 6. TypedEventBus — suspendHandler on a typed bus view

fun handlerTypedBus() = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val networkBus = EventBus().typed<NetworkEvent>()
    val received = mutableListOf<Int>()

    val sub = networkBus.suspendHandler<PacketReceived>(scope = scope) { e ->
        synchronized(received) { received += e.bytes.size }
    }

    networkBus.post(PacketReceived(ByteArray(64)))
    networkBus.post(ConnectionLost("timeout")) // not handled — different type
    networkBus.post(PacketReceived(ByteArray(128)))

    delay(100.milliseconds)
    println("Received sizes: $received") // [64, 128]
    sub.cancel()
}

fun main() {
    println("=== 1. Parallel ===")
    handlerParallelBehaviour()
    println("\n=== 2. DiscardIfBusy ===")
    handlerDiscardIfBusyBehaviour()
    println("\n=== 3. ReplaceLatest ===")
    handlerReplaceLatestBehaviour()
    println("\n=== 4. Priority ===")
    handlerWithPriority()
    println("\n=== 5. Subscription cancellation ===")
    handlerSubscriptionCancellation()
    println("\n=== 6. TypedEventBus ===")
    handlerTypedBus()
}
