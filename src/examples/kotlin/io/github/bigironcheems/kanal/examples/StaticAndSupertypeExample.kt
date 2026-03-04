package io.github.bigironcheems.kanal.examples

import io.github.bigironcheems.kanal.*

// Static subscribers
//
// Kotlin objects and Java static methods can be registered as subscribers.
// Use subscribeStatic / unsubscribeStatic with the class token.
// Methods must be annotated with @Subscribe and @JvmStatic.

object ServerListener {
    @JvmStatic
    @Subscribe(priority = Priority.HIGH)
    fun onJump(e: PlayerJumpEvent) {
        println("[Server] ${e.player} jumped")
    }
}

fun staticSubscriberUsage() {
    val bus = EventBus()

    bus.subscribeStatic(ServerListener::class.java)
    println("After subscribeStatic:")
    bus.post(PlayerJumpEvent("Steve"))

    bus.unsubscribeStatic(ServerListener::class.java)
    println("After unsubscribeStatic:")
    bus.post(PlayerJumpEvent("Alex"))  // ServerListener.onJump not called
    println("(no output above; static handler removed)")
}

// Supertype dispatch
//
// Posting a SubEvent also reaches handlers registered for any superclass or
// interface in its hierarchy, in priority order across all layers.

open class BaseAttackEvent(val damage: Double) : Event
class CriticalHitEvent(damage: Double, val multiplier: Double) : BaseAttackEvent(damage)

fun supertypeDispatch() {
    val bus = EventBus()

    bus.subscribe<BaseAttackEvent> { e -> println("Attack for ${e.damage}") }
    bus.subscribe<CriticalHitEvent> { e -> println("Critical x${e.multiplier}!") }

    bus.post(CriticalHitEvent(damage = 10.0, multiplier = 2.0))
    // Output:
    //   Critical x2.0!   (CriticalHitEvent handler, higher specificity)
    //   Attack for 10.0  (BaseAttackEvent handler)
}

fun main() {
    println("=== Static subscriber ===")
    staticSubscriberUsage()
    println("\n=== Supertype dispatch ===")
    supertypeDispatch()
}
