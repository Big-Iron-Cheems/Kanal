package io.github.bigironcheems.kanal

/**
 * Optional interface for events that support cancellation.
 *
 * When a [Cancellable] event is posted, the bus stops dispatching as soon as
 * [isCancelled] is `true`; remaining handlers are skipped.
 *
 * ```kotlin
 * class BlockBreakEvent : Event, Cancellable {
 *     override var isCancelled = false
 * }
 * ```
 *
 * Java: Kotlin compiles `var isCancelled` to `isCancelled()` / `setCancelled(boolean)`:
 * ```java
 * public class BlockBreakEvent implements Event, Cancellable {
 *     private boolean cancelled = false;
 *     @Override public boolean isCancelled() { return cancelled; }
 *     @Override public void setCancelled(boolean v) { cancelled = v; }
 * }
 * ```
 *
 * ### Async dispatch
 *
 * Cancellation is automatically thread-safe when the event is posted via [EventBus.postAsync]
 * or to a bus with async handlers. The bus wraps the cancellation check in an
 * [java.util.concurrent.atomic.AtomicBoolean] for the duration of the async dispatch chain,
 * making cross-thread cancellation visibility transparent. The final cancelled state is written
 * back to this field once after all handlers have completed.
 */
public interface Cancellable {
    /** Whether this event has been cancelled. */
    public var isCancelled: Boolean

    /** Convenience method to cancel the event. Equivalent to `isCancelled = true`. */
    public fun cancel() {
        isCancelled = true
    }
}
