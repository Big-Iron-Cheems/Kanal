package io.github.bigironcheems.kanal.examples

import io.github.bigironcheems.kanal.*

// Annotation-based subscriber

class GameListener {
    @Subscribe(priority = Priority.HIGH)
    fun onJump(e: PlayerJumpEvent) {
        println("${e.player} jumped!")
    }

    @Subscribe
    fun onBreak(e: BlockBreakEvent) {
        e.cancel()
    }
}

// Basic usage

fun basicUsage() {
    val bus = EventBus()

    // Annotation-based
    bus.subscribe(GameListener())
    bus.post(PlayerJumpEvent("Steve"))

    // Lambda; returns a Subscription token for later removal
    val sub = bus.subscribe<PlayerJumpEvent> { e -> println(e.player) }
    sub.cancel()

    // Lambda with priority
    val sub2 = bus.subscribe<BlockBreakEvent>(Priority.HIGH) { e -> e.cancel() }
    sub2.cancel()
}

// Cancellable

fun cancellableUsage() {
    val bus = EventBus()
    bus.subscribe<BlockBreakEvent>(Priority.HIGH) { e -> e.cancel() }

    val result = bus.post(BlockBreakEvent("stone"))
    println("BlockBreakEvent cancelled: ${result.isCancelled}")  // true
}

// Modifiable

fun modifiableUsage() {
    val bus = EventBus()
    bus.subscribe<DamageEvent> { e -> e.value *= 0.5 }

    val damage = bus.post(DamageEvent(10.0)).value
    println("Damage after halving: $damage")  // 5.0
}

// Error handling

fun customErrorHandler() {
    val bus = EventBus { throwable: Throwable ->
        println("Caught handler error: ${throwable.message}")
    }
    bus.subscribe<PlayerJumpEvent> { throw RuntimeException("handler blew up") }
    bus.post(PlayerJumpEvent("Alex"))
}

fun main() {
    println("=== Basic usage ===")
    basicUsage()
    println("\n=== Cancellable ===")
    cancellableUsage()
    println("\n=== Modifiable ===")
    modifiableUsage()
    println("\n=== Custom error handler ===")
    customErrorHandler()
}
