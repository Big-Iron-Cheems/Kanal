package io.github.bigironcheems.kanal.coroutines

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Priority
import io.github.bigironcheems.kanal.Subscription
import io.github.bigironcheems.kanal.subscribe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Controls how a suspend handler behaves when a new event arrives.
 */
public sealed interface SuspendHandlerBehaviour {

    /**
     * Launches a new coroutine for every event regardless of whether
     * a previous one is still running. All invocations run concurrently.
     */
    public data object Parallel : SuspendHandlerBehaviour

    /**
     * Skips the new event if the previous coroutine is still running.
     * Useful for handlers that must not overlap (e.g. UI updates, debounced actions).
     */
    public data object DiscardIfBusy : SuspendHandlerBehaviour

    /**
     * Cancels the previous coroutine and launches a new one for the incoming event.
     * Useful for handlers that should always process the latest event.
     */
    public data object ReplaceLatest : SuspendHandlerBehaviour
}

/**
 * Registers a suspend [handler] for event type [T] and returns a [Subscription] token.
 *
 * A [CoroutineScope] backed by a [SupervisorJob] and [Dispatchers.Default] is created
 * internally. Cancelling the returned [Subscription] cancels the scope and all
 * running handler coroutines.
 *
 * ```kotlin
 * val sub = bus.suspendHandler<PlayerJumpEvent> { e ->
 *     delay(100)
 *     println(e.player)
 * }
 * sub.cancel()
 * ```
 *
 * @param priority        Dispatch priority; defaults to [Priority.NORMAL].
 * @param behaviour       Controls concurrency when events arrive faster than handlers complete.
 * @param handler         The suspend function invoked for each matching event.
 */
public inline fun <reified T : Event> EventBus.suspendHandler(
    priority: Int = Priority.NORMAL,
    behaviour: SuspendHandlerBehaviour = SuspendHandlerBehaviour.Parallel,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    crossinline handler: suspend (T) -> Unit
): Subscription {
    val activeJob = AtomicReference<Job?>(null)

    val sub = subscribe<T>(priority) { event ->
        when (behaviour) {
            SuspendHandlerBehaviour.Parallel -> {
                scope.launch { handler(event) }
            }
            SuspendHandlerBehaviour.DiscardIfBusy -> {
                val current = activeJob.get()
                if (current?.isActive != true) {
                    val job = scope.launch { handler(event) }
                    activeJob.set(job)
                }
            }
            SuspendHandlerBehaviour.ReplaceLatest -> {
                activeJob.getAndSet(null)?.cancel()
                val job = scope.launch(start = CoroutineStart.DEFAULT) { handler(event) }
                activeJob.set(job)
            }
        }
    }

    return object : Subscription {
        override fun cancel() {
            sub.cancel()
            scope.coroutineContext[Job]?.cancel()
        }
    }
}
