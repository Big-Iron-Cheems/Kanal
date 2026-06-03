package io.github.bigironcheems.kanal.coroutines.examples

import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Priority
import io.github.bigironcheems.kanal.coroutines.nextEvent
import io.github.bigironcheems.kanal.coroutines.nextEventOrNull
import io.github.bigironcheems.kanal.subscribe
import io.github.bigironcheems.kanal.typed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Demonstrates [nextEvent] and [nextEventOrNull] - suspending until a single
 * matching event arrives.
 *
 * These examples use [kotlinx.coroutines.Dispatchers.Unconfined] when launching
 * the awaiter coroutine. This runs the coroutine eagerly on the current thread
 * until the first suspension point ([kotlinx.coroutines.suspendCancellableCoroutine]),
 * guaranteeing the subscription is registered before any event is posted.
 * In a real coroutine scope that is already running, no special dispatcher is needed.
 */

// 1. Basic nextEvent - suspend until the first matching event

fun nextEventBasic() = runBlocking {
    val bus = EventBus()

    val job = launch(Dispatchers.Unconfined) {
        val event = bus.nextEvent<PlayerJumpEvent>()
        println("${event.player} jumped")
    }

    bus.post(PlayerJumpEvent("Steve"))
    job.join()
}

// 2. nextEvent ignores non-matching types

fun nextEventIgnoresOtherTypes() = runBlocking {
    val bus = EventBus()

    val job = launch(Dispatchers.Unconfined) {
        val event = bus.nextEvent<PlayerJumpEvent>()
        println("Got: ${event.player}")
    }

    bus.post(BlockBreakEvent("stone"))
    bus.post(PlayerJumpEvent("Alex"))
    job.join()
}

// 3. nextEvent with predicate - skip events that do not match

fun nextEventWithPredicate() = runBlocking {
    val bus = EventBus()

    val job = launch(Dispatchers.Unconfined) {
        val event = bus.nextEvent<DamageEvent> { it.value > 5.0 }
        println("High damage: ${event.value}")
    }

    bus.post(DamageEvent(2.0))
    bus.post(DamageEvent(3.0))
    bus.post(DamageEvent(8.0))
    job.join()
}

// 4. nextEvent with priority

fun nextEventWithPriority() = runBlocking {
    val bus = EventBus()
    val order = mutableListOf<String>()

    bus.subscribe<PlayerJumpEvent>(Priority.NORMAL) { order += "normal" }

    val job = launch(Dispatchers.Unconfined) {
        bus.nextEvent<PlayerJumpEvent>(priority = Priority.HIGH)
        order += "nextEvent-high"
    }

    bus.post(PlayerJumpEvent("Steve"))
    job.join()

    println("Order: $order")
}

// 5. nextEvent unregisters after first match - subsequent events not received

fun nextEventSingleShot() = runBlocking {
    val bus = EventBus()
    var count = 0

    val job = launch(Dispatchers.Unconfined) {
        bus.nextEvent<PlayerJumpEvent>()
        count++
    }

    bus.post(PlayerJumpEvent("Steve"))
    job.join()
    println("Count: $count")

    bus.post(PlayerJumpEvent("Alex"))
    println("Count after second post: $count")
}

// 6. nextEventOrNull - returns null on timeout

fun nextEventOrNullTimeout() = runBlocking {
    val bus = EventBus()

    val event = bus.nextEventOrNull<PlayerJumpEvent>(50.milliseconds)
    if (event != null) {
        println("Got: ${event.player}")
    } else {
        println("Timed out - no event arrived")
    }
}

// 7. nextEventOrNull - returns event when received before timeout

fun nextEventOrNullHit() = runBlocking {
    val bus = EventBus()

    val job = launch(Dispatchers.Unconfined) {
        val event = bus.nextEventOrNull<PlayerJumpEvent>(5.seconds)
        println("Got: ${event?.player ?: "null"}")
    }

    bus.post(PlayerJumpEvent("Steve"))
    job.join()
}

// 8. nextEventOrNull with predicate

fun nextEventOrNullWithPredicate() = runBlocking {
    val bus = EventBus()

    val job = launch(Dispatchers.Unconfined) {
        val event = bus.nextEventOrNull<DamageEvent>(5.seconds) { it.value > 5.0 }
        println("High damage: ${event?.value ?: "timed out"}")
    }

    bus.post(DamageEvent(2.0))
    bus.post(DamageEvent(8.0))
    job.join()
}

// 9. TypedEventBus - nextEvent on a typed bus view

fun nextEventTypedBus() = runBlocking {
    val networkBus = EventBus().typed<NetworkEvent>()

    val job = launch(Dispatchers.Unconfined) {
        val event = networkBus.nextEvent<PacketReceived>()
        println("Received ${event.bytes.size} bytes")
    }

    networkBus.post(ConnectionLost("timeout"))
    networkBus.post(PacketReceived(ByteArray(64)))
    job.join()
}

// 10. TypedEventBus - nextEventOrNull with timeout

fun nextEventOrNullTypedBus() = runBlocking {
    val networkBus = EventBus().typed<NetworkEvent>()

    val job = launch(Dispatchers.Unconfined) {
        val event = networkBus.nextEventOrNull<PacketReceived>(5.seconds)
        println("Received: ${event?.bytes?.size ?: "timed out"}")
    }

    networkBus.post(PacketReceived(ByteArray(32)))
    job.join()
}

fun main() {
    println("=== 1. Basic nextEvent ===")
    nextEventBasic()
    println("\n=== 2. Ignores other types ===")
    nextEventIgnoresOtherTypes()
    println("\n=== 3. With predicate ===")
    nextEventWithPredicate()
    println("\n=== 4. With priority ===")
    nextEventWithPriority()
    println("\n=== 5. Single-shot ===")
    nextEventSingleShot()
    println("\n=== 6. nextEventOrNull timeout ===")
    nextEventOrNullTimeout()
    println("\n=== 7. nextEventOrNull hit ===")
    nextEventOrNullHit()
    println("\n=== 8. nextEventOrNull with predicate ===")
    nextEventOrNullWithPredicate()
    println("\n=== 9. TypedEventBus nextEvent ===")
    nextEventTypedBus()
    println("\n=== 10. TypedEventBus nextEventOrNull ===")
    nextEventOrNullTypedBus()
}
