package io.github.bigironcheems.kanal.examples

import io.github.bigironcheems.kanal.*

// Shared event types used across multiple example files.

class PlayerJumpEvent(val player: String) : Event
class BlockBreakEvent(val block: String) : Event, Cancellable {
    override var isCancelled: Boolean = false
}

class DamageEvent(override var value: Double) : Event, Modifiable<Double>
