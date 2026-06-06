package io.github.bigironcheems.kanal.coroutines

import io.github.bigironcheems.kanal.*
import kotlinx.coroutines.*
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
 * The subscription is registered synchronously before returning. Events can be posted
 * immediately after this call returns without any timing concerns.
 *
 * ```kotlin
 * val sub = bus.suspendHandler<PlayerJumpEvent> { e ->
 *     delay(100)
 *     println(e.player)
 * }
 * sub.cancel()
 * ```
 *
 * @param priority  Dispatch priority; defaults to [Priority.NORMAL].
 * @param behaviour Controls concurrency when events arrive faster than handlers complete.
 * @param scope     Coroutine scope for handler execution. When `null` (default), an internal
 *                  scope is created and cancelled when the subscription is cancelled.
 *                  When an explicit scope is provided, it is used but never cancelled by
 *                  this subscription - the caller retains full lifecycle ownership.
 *                  Prefer providing an explicit scope in production code.
 * @param handler   The suspend function invoked for each matching event.
 */
public inline fun <reified T : Event> EventBus.suspendHandler(
    priority: Int = Priority.NORMAL,
    behaviour: SuspendHandlerBehaviour = SuspendHandlerBehaviour.Parallel,
    scope: CoroutineScope? = null,
    crossinline handler: suspend (T) -> Unit
): Subscription {
    val effectiveScope = scope ?: CoroutineScope(Dispatchers.Default + SupervisorJob())
    val ownsScope = scope == null
    val activeJob = AtomicReference<Job?>(null)

    val sub = subscribe<T>(priority) { event ->
        when (behaviour) {
            SuspendHandlerBehaviour.Parallel -> {
                effectiveScope.launch { handler(event) }
            }

            SuspendHandlerBehaviour.DiscardIfBusy -> {
                val current = activeJob.get()
                if (current?.isActive != true) {
                    val job = effectiveScope.launch { handler(event) }
                    activeJob.set(job)
                }
            }

            SuspendHandlerBehaviour.ReplaceLatest -> {
                activeJob.getAndSet(null)?.cancel()
                val job = effectiveScope.launch(start = CoroutineStart.DEFAULT) { handler(event) }
                activeJob.set(job)
            }
        }
    }

    return object : Subscription {
        override fun cancel() {
            sub.cancel()
            if (ownsScope) effectiveScope.coroutineContext[Job]?.cancel()
        }
    }
}

/**
 * Registers a suspend [handler] for event type [T] on this [TypedEventBus]
 * and returns a [Subscription] token.
 *
 * Delegates to [EventBus.suspendHandler] on the underlying bus.
 *
 * ```kotlin
 * val networkBus = EventBus().typed<NetworkEvent>()
 * val sub = networkBus.suspendHandler<PacketReceived>(scope = viewModelScope) { e ->
 *     delay(100)
 *     handle(e)
 * }
 * sub.cancel()
 * ```
 *
 * @param priority  Dispatch priority; defaults to [Priority.NORMAL].
 * @param behaviour Controls concurrency when events arrive faster than handlers complete.
 * @param scope     Coroutine scope for handler execution. When `null` (default), an internal
 *                  scope is created and cancelled when the subscription is cancelled.
 *                  When an explicit scope is provided, it is used but never cancelled by
 *                  this subscription - the caller retains full lifecycle ownership.
 *                  Prefer providing an explicit scope in production code.
 * @param handler   The suspend function invoked for each matching event.
 */
public inline fun <reified T : Event> TypedEventBus<in T>.suspendHandler(
    priority: Int = Priority.NORMAL,
    behaviour: SuspendHandlerBehaviour = SuspendHandlerBehaviour.Parallel,
    scope: CoroutineScope? = null,
    crossinline handler: suspend (T) -> Unit
): Subscription = delegate.suspendHandler<T>(priority, behaviour, scope, handler)
