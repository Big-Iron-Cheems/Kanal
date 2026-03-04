package io.github.bigironcheems.kanal.examples

import io.github.bigironcheems.kanal.*

// Wildcard listeners
//
// subscribeAll fires for every event posted to the bus, interleaved with typed
// handlers by priority. A wildcard at Priority.HIGHEST runs before all typed
// handlers at Priority.NORMAL; Priority.LOWEST runs after everything.

fun wildcardUsage() {
    val bus = EventBus()

    // Audit hook; runs before any typed handler
    val audit = bus.subscribeAll(Priority.HIGHEST) { e ->
        println("[AUDIT] dispatching ${e::class.simpleName}")
    }

    // Logging; runs after all domain handlers
    val log = bus.subscribeAll(Priority.LOWEST) { e ->
        println("[LOG] dispatched ${e::class.simpleName}")
    }

    bus.subscribe<PlayerJumpEvent> { e -> println("${e.player} jumped") }
    bus.post(PlayerJumpEvent("Steve"))
    // Output order: [AUDIT], jumped, [LOG]

    audit.cancel()
    log.cancel()
}

// isListeningAll

fun wildcardQuery() {
    val bus = EventBus()
    println(bus.isListeningAll()) // false

    val sub = bus.subscribeAll { }
    println(bus.isListeningAll()) // true

    sub.cancel()
    println(bus.isListeningAll()) // false
}

fun main() {
    println("=== Wildcard usage ===")
    wildcardUsage()
    println("\n=== isListeningAll query ===")
    wildcardQuery()
}
