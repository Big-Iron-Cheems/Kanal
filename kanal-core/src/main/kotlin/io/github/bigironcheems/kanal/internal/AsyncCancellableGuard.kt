package io.github.bigironcheems.kanal.internal

import io.github.bigironcheems.kanal.Cancellable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Makes cancellation checks thread-safe during async dispatch without requiring any special
 * annotations or atomic types on the user's `isCancelled` field.
 *
 * ### Design
 *
 * The `CompletableFuture` chain that drives async dispatch provides a **happens-before** edge
 * between each successive step: the completion of stage N happens-before the start of stage N+1.
 * Any write to `delegate.isCancelled` performed inside stage N is therefore visible to the code
 * running in stage N+1 due to this edge.
 *
 * The guard exploits this by **polling** `delegate.isCancelled` at the *start* of each chain step
 * via [syncFromDelegate], which runs after the prior step's future has completed. If the prior
 * handler set `isCancelled = true` on the event, that write is safely visible here due to the
 * happens-before edge, and the [AtomicBoolean] is updated accordingly.
 *
 * Subsequent cancellation checks in the same step read [isCancelled] from the [AtomicBoolean],
 * which is thread-safe for the concurrent reads that `thenRunAsync` may cause.
 *
 * After the entire chain completes, [flush] does one final read of `delegate.isCancelled`
 * (safe under `join()`'s happens-before) to capture any write from the very last step, then
 * ensures the delegate reflects the final cancelled state.
 *
 * ### Sync path
 *
 * Only created when an executor is configured and the event implements [Cancellable].
 * The sync dispatch loop reads `Cancellable.isCancelled` directly on the original event,
 * no wrapper, no allocation.
 */
internal class AsyncCancellableGuard(
    private val delegate: Cancellable,
) {
    private val flag = AtomicBoolean(delegate.isCancelled)

    /** Returns `true` if the event has been cancelled. */
    val isCancelled: Boolean get() = flag.get()

    /**
     * Reads the current `delegate.isCancelled` value and syncs it into the atomic.
     * Must be called at the start of each chain step, *after* the prior future has completed,
     * so the happens-before edge from the future chain makes the delegate's field visible.
     */
    fun syncFromDelegate() {
        if (delegate.isCancelled) flag.set(true)
    }

    /**
     * Writes the final cancellation state back to the original event.
     * Must be called *after* the dispatch chain has joined / the future has completed,
     * so the happens-before edge from `join()` / `thenApply` makes all handler writes visible.
     * Reads `delegate.isCancelled` one final time to capture any write from the last step,
     * then propagates the result.
     */
    fun flush() {
        // The join()/thenApply happens-before edge makes all handler writes visible here.
        val finalValue = flag.get() || delegate.isCancelled
        if (finalValue) delegate.isCancelled = true
    }
}
