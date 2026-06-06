package io.github.bigironcheems.kanal.coroutines.examples

import io.github.bigironcheems.kanal.Cancellable
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.coroutines.postSuspend
import io.github.bigironcheems.kanal.subscribe
import io.github.bigironcheems.kanal.typed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Demonstrates [postSuspend] - dispatching events from a coroutine context.
 *
 * [postSuspend] wraps [io.github.bigironcheems.kanal.EventBus.post] in a
 * [kotlinx.coroutines.withContext] call, suspending until all handlers finish.
 * Use it when posting from a coroutine and you want to control which dispatcher
 * the dispatch runs on.
 *
 * For most cases [io.github.bigironcheems.kanal.EventBus.post] is sufficient.
 * [postSuspend] is useful when:
 * - posting from a coroutine that must not block its dispatcher
 * - you need explicit control over which thread dispatch runs on
 */

// 1. Basic postSuspend - dispatch from a coroutine

fun postSuspendBasic() = runBlocking {
    val bus = EventBus()
    bus.subscribe<PlayerJumpEvent> { e -> println("${e.player} jumped") }

    bus.postSuspend(PlayerJumpEvent("Steve"))
}

// 2. postSuspend with custom dispatcher - dispatch on a specific thread pool

fun postSuspendWithDispatcher() = runBlocking {
    val bus = EventBus()
    bus.subscribe<PlayerJumpEvent> { _ ->
        println("Handler on: ${Thread.currentThread().name}")
    }

    println("Posting from: ${Thread.currentThread().name}")
    bus.postSuspend(PlayerJumpEvent("Steve"), Dispatchers.IO)
}

// 3. postSuspend with Dispatchers.Unconfined - explicit no-hop dispatch
// Equivalent to the default when called without a context argument,
// but explicit when you want to document intent.

fun postSuspendUnconfined() = runBlocking {
    val bus = EventBus()
    bus.subscribe<PlayerJumpEvent> { e -> println("${e.player} jumped") }

    // Unconfined runs on the calling thread - cost closest to plain post()
    bus.postSuspend(PlayerJumpEvent("Steve"), Dispatchers.Unconfined)
}

// 4. postSuspend suspends until all handlers finish

fun postSuspendAwaitsHandlers() = runBlocking {
    val bus = EventBus()
    val results = mutableListOf<String>()

    bus.subscribe<PlayerJumpEvent>(io.github.bigironcheems.kanal.Priority.HIGH) { e ->
        results += "high: ${e.player}"
    }
    bus.subscribe<PlayerJumpEvent>(io.github.bigironcheems.kanal.Priority.LOW) { e ->
        results += "low: ${e.player}"
    }

    bus.postSuspend(PlayerJumpEvent("Steve"))
    // postSuspend has returned - all handlers have run
    println("Results: $results") // [high: Steve, low: Steve]
}

// 5. postSuspend with cancellable events

fun postSuspendCancellable() = runBlocking {
    val bus = EventBus()

    class AttackEvent(val damage: Double) : io.github.bigironcheems.kanal.Event, Cancellable {
        override var isCancelled = false
    }

    bus.subscribe<AttackEvent>(io.github.bigironcheems.kanal.Priority.HIGH) { e ->
        if (e.damage > 10.0) {
            e.cancel()
            println("Attack cancelled: damage ${e.damage} exceeds limit")
        }
    }
    bus.subscribe<AttackEvent>(io.github.bigironcheems.kanal.Priority.LOW) { e ->
        println("Attack landed: ${e.damage}") // only if not cancelled
    }

    val result = bus.postSuspend(AttackEvent(5.0))
    println("Cancelled: ${result.isCancelled}") // false

    val result2 = bus.postSuspend(AttackEvent(15.0))
    println("Cancelled: ${result2.isCancelled}") // true
}

// 6. postSuspend from multiple coroutines concurrently

fun postSuspendConcurrent() = runBlocking {
    val bus = EventBus()
    val received = mutableListOf<String>()

    bus.subscribe<PlayerJumpEvent> { e ->
        synchronized(received) { received += e.player }
    }

    val jobs = listOf("Steve", "Alex", "Sam").map { player ->
        launch {
            bus.postSuspend(PlayerJumpEvent(player))
        }
    }

    jobs.joinAll()
    println("Received ${received.size} events: $received")
}

// 7. TypedEventBus - postSuspend via typed bus delegate

fun postSuspendTypedBus() = runBlocking {
    val networkBus = EventBus().typed<NetworkEvent>()
    networkBus.subscribe<PacketReceived> { e ->
        println("Received ${e.bytes.size} bytes")
    }

    // postSuspend is an EventBus extension - use delegate for typed bus
    networkBus.delegate.postSuspend(PacketReceived(ByteArray(64)))
}

fun main() {
    println("=== 1. Basic postSuspend ===")
    postSuspendBasic()
    println("\n=== 2. With custom dispatcher ===")
    postSuspendWithDispatcher()
    println("\n=== 3. Unconfined dispatcher ===")
    postSuspendUnconfined()
    println("\n=== 4. Awaits all handlers ===")
    postSuspendAwaitsHandlers()
    println("\n=== 5. Cancellable events ===")
    postSuspendCancellable()
    println("\n=== 6. Concurrent posting ===")
    postSuspendConcurrent()
    println("\n=== 7. TypedEventBus ===")
    postSuspendTypedBus()
}
