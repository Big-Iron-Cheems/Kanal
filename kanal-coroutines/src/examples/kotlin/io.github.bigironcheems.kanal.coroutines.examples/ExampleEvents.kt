package io.github.bigironcheems.kanal.coroutines.examples

import io.github.bigironcheems.kanal.Cancellable
import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.Modifiable

// Shared event types used across coroutines examples.

class PlayerJumpEvent(val player: String) : Event

class BlockBreakEvent(@Suppress("unused") val block: String) : Event, Cancellable {
    override var isCancelled: Boolean = false
}

class DamageEvent(override var value: Double) : Event, Modifiable<Double>

sealed interface NetworkEvent : Event
class PacketReceived(val bytes: ByteArray) : NetworkEvent
class ConnectionLost(@Suppress("unused") val reason: String) : NetworkEvent

class TickEvent(val tick: Long) : Event
