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
 */
public interface Cancellable {
    /** Whether this event has been cancelled. */
    public var isCancelled: Boolean

    /** Convenience method to cancel the event. Equivalent to `isCancelled = true`. */
    public fun cancel() {
        isCancelled = true
    }
}
