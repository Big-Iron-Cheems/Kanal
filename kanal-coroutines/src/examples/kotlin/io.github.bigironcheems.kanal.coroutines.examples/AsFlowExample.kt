package io.github.bigironcheems.kanal.coroutines.examples

import io.github.bigironcheems.kanal.*
import io.github.bigironcheems.kanal.coroutines.asFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Demonstrates [asFlow] — exposing an [EventBus] event type as a Kotlin [kotlinx.coroutines.flow.Flow].
 *
 * The subscription is registered when collection starts and removed when the collector
 * is cancelled or the flow completes naturally.
 *
 * ### Subscription timing
 * `asFlow` registers its subscription inside the collector coroutine body. In a concurrent
 * context the subscription may not be active immediately after [launch].
 *
 * These examples use [Dispatchers.Unconfined] when launching the collector, which runs
 * the coroutine eagerly on the current thread until the first suspension point (`awaitClose`).
 * This guarantees `subscribe()` has been called before any event is posted.
 *
 * If you only need to handle events with suspend logic and don't need Flow operators,
 * prefer [io.github.bigironcheems.kanal.coroutines.suspendHandler] which registers
 * synchronously and avoids this concern entirely.
 */

// 1. Basic collection — collect N events then terminate via take()

fun flowBasicCollection() = runBlocking {
    val bus = EventBus()

    val job = launch(Dispatchers.Unconfined) {
        bus.asFlow<PlayerJumpEvent>()
            .take(2)
            .collect { e -> println("${e.player} jumped") }
    }

    bus.post(PlayerJumpEvent("Steve"))
    bus.post(PlayerJumpEvent("Alex"))
    job.join()
}

// 2. Flow operators — filter, take, toList

fun flowWithFlowOperators() = runBlocking {
    val bus = EventBus()

    val job = launch(Dispatchers.Unconfined) {
        bus.asFlow<PlayerJumpEvent>()
            .filter { it.player.startsWith("S") }
            .take(2)
            .toList()
            .forEach { e -> println("Filtered: ${e.player}") }
    }

    bus.post(PlayerJumpEvent("Steve"))  // collected
    bus.post(PlayerJumpEvent("Alex"))   // filtered out
    bus.post(PlayerJumpEvent("Sam"))    // collected — take(2) completes
    job.join()
}

// 3. Priority — flow collector at HIGH fires before NORMAL subscriber

fun flowWithPriority() = runBlocking {
    val bus = EventBus()
    val order = mutableListOf<String>()

    bus.subscribe<PlayerJumpEvent> { order += "normal" }

    val job = launch(Dispatchers.Unconfined) {
        bus.asFlow<PlayerJumpEvent>(Priority.HIGH)
            .take(1)
            .collect { order += "flow-high" }
    }

    bus.post(PlayerJumpEvent("Steve"))
    job.join()

    println("Order: $order") // [flow-high, normal]
}

// 4. Subscription lifetime — handler registered on collect, removed on cancellation

fun flowSubscriptionLifetime() = runBlocking {
    val bus = EventBus()

    val job = launch(Dispatchers.Unconfined) {
        bus.asFlow<PlayerJumpEvent>().collect { }
    }

    println("Listening: ${bus.isListening<PlayerJumpEvent>()}") // true
    job.cancelAndJoin()
    println("Listening: ${bus.isListening<PlayerJumpEvent>()}") // false
}

// 5. TypedEventBus — asFlow on a typed bus view

fun flowTypedBus() = runBlocking {
    val networkBus = EventBus().typed<NetworkEvent>()

    val job = launch(Dispatchers.Unconfined) {
        networkBus.asFlow<PacketReceived>()
            .take(1)
            .collect { e -> println("Received ${e.bytes.size} bytes") }
    }

    networkBus.post(ConnectionLost("timeout")) // not collected — different type
    networkBus.post(PacketReceived(ByteArray(128)))
    job.join()
}

// 6. Collecting into a list — take N events and terminate

fun flowCollectIntoList() = runBlocking {
    val bus = EventBus()

    val job = launch(Dispatchers.Unconfined) {
        val events = bus.asFlow<TickEvent>()
            .take(3)
            .toList()
        println("Collected ticks: ${events.map { it.tick }}") // [1, 2, 3]
    }

    repeat(5) { i -> bus.post(TickEvent(i + 1L)) }
    job.join()
}

fun main() {
    println("=== 1. Basic collection ===")
    flowBasicCollection()
    println("\n=== 2. Flow operators ===")
    flowWithFlowOperators()
    println("\n=== 3. Priority ===")
    flowWithPriority()
    println("\n=== 4. Subscription lifetime ===")
    flowSubscriptionLifetime()
    println("\n=== 5. TypedEventBus ===")
    flowTypedBus()
    println("\n=== 6. Collect into list ===")
    flowCollectIntoList()
}
