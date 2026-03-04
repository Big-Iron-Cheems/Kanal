package io.github.bigironcheems.kanal

/**
 * Handle returned by lambda-based [EventBus.subscribe] overloads.
 *
 * Call [cancel] to remove the handler from the bus. Implements [AutoCloseable]
 * so it can be used in try-with-resources / Kotlin `use { }` blocks.
 *
 * ```kotlin
 * val sub = bus.subscribe<TickEvent> { tick() }
 * sub.cancel()
 *
 * // Or scope it:
 * bus.subscribe<TickEvent> { tick() }.use { /* handler active here */ }
 * ```
 *
 * Java:
 * ```java
 * Subscription sub = bus.subscribe(TickEvent.class, Priority.NORMAL, e -> tick());
 * sub.cancel();
 * ```
 */
public interface Subscription : AutoCloseable {
    /** Removes this handler from the bus. Safe to call more than once; subsequent calls are no-ops. */
    public fun cancel()

    /** Alias for [cancel]; satisfies [AutoCloseable] for try-with-resources / `use { }`. */
    override fun close(): Unit = cancel()
}
