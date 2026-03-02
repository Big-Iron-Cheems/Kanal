package io.github.bigironcheems.kanal

/**
 * A handle returned by lambda-based [EventBus.subscribe] overloads.
 *
 * Call [cancel] to remove the handler from the bus. Also implements
 * [AutoCloseable] for use in try-with-resources / Kotlin `use { }` blocks.
 *
 * ```kotlin
 * val sub = bus.subscribe<TickEvent> { tick() }
 * // later:
 * sub.cancel()
 * ```
 *
 * ```java
 * Subscription sub = bus.subscribe(TickEvent.class, Priority.NORMAL, e -> tick());
 * // later:
 * sub.cancel();
 * ```
 */
public interface Subscription : AutoCloseable {
    /** Removes this handler from the bus. Idempotent; safe to call more than once. */
    public fun cancel()

    /** Alias for [cancel]; allows use in try-with-resources / `use { }`. */
    override fun close(): Unit = cancel()
}
