package io.github.bigironcheems.kanal

/**
 * Optional interface for events that support cancellation.
 *
 * When a [Cancellable] event is posted, dispatch stops as soon as any handler sets
 * [isCancelled] to `true`; remaining handlers are skipped.
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
 * Cancellation is automatically thread-safe when the event is dispatched via [EventBus.postAsync]
 * or to a bus with async handlers. A plain `var isCancelled = false` field is sufficient.
 */
public interface Cancellable {
    /** Whether this event has been cancelled. Set to `true` to stop further dispatch. */
    public var isCancelled: Boolean

    /** Sets [isCancelled] to `true`. Equivalent to `isCancelled = true`. */
    public fun cancel() {
        isCancelled = true
    }
}
