package io.github.bigironcheems.kanal.examples

import io.github.bigironcheems.kanal.*

// Typed bus
//
// TypedEventBus<E> restricts post and subscribe to subtypes of E at compile
// time. Works best with sealed hierarchies; sealed interface prevents external
// types from satisfying T : E, so the constraint cannot be circumvented.

sealed interface NetworkEvent : Event
class PacketReceived(val bytes: ByteArray) : NetworkEvent
class ConnectionLost(val reason: String) : NetworkEvent

sealed interface UIEvent : Event
class ButtonClickEvent(val id: String) : UIEvent

fun typedBusUsage() {
    val underlying = EventBus()

    // Two domain-scoped views over the same underlying bus
    val networkBus = underlying.typed<NetworkEvent>()
    val uiBus = underlying.typed<UIEvent>()

    networkBus.subscribe<PacketReceived> { e ->
        println("Received ${e.bytes.size} bytes")
    }
    networkBus.subscribe<ConnectionLost> { e ->
        println("Lost connection: ${e.reason}")
    }

    uiBus.subscribe<ButtonClickEvent> { e ->
        println("Button clicked: ${e.id}")
    }

    networkBus.post(PacketReceived(byteArrayOf(1, 2, 3)))
    networkBus.post(ConnectionLost("timeout"))
    uiBus.post(ButtonClickEvent("submit"))

    // networkBus.post(ButtonClickEvent("submit"))  // compile error; ButtonClickEvent is not a NetworkEvent
}

// Typed bus; delegate access

fun typedBusDelegate() {
    val underlying = EventBus()
    val networkBus = underlying.typed<NetworkEvent>()

    networkBus.subscribe<PacketReceived> { }

    // Operations not on the TypedEventBus interface go through delegate
    networkBus.delegate.unsubscribeAll()
    println(networkBus.isListening<PacketReceived>()) // false
}

// Typed bus; unsubscribe annotation-based handler

class NetworkHandler {
    @Subscribe
    fun onPacket(e: PacketReceived) {
        println("handling packet")
    }
}

fun typedBusAnnotationUnsubscribe() {
    val networkBus = EventBus().typed<NetworkEvent>()
    val handler = NetworkHandler()

    networkBus.subscribe(handler)
    println("Before unsubscribe:")
    networkBus.post(PacketReceived(byteArrayOf()))  // fires

    networkBus.unsubscribe(handler)
    println("After unsubscribe:")
    networkBus.post(PacketReceived(byteArrayOf()))  // does not fire
    println("(no output above; handler removed)")
}

fun main() {
    println("=== Typed bus usage ===")
    typedBusUsage()
    println("\n=== Typed bus delegate ===")
    typedBusDelegate()
    println("\n=== Typed bus annotation unsubscribe ===")
    typedBusAnnotationUnsubscribe()
}
