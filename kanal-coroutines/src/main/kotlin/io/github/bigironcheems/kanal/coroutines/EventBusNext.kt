package io.github.bigironcheems.kanal.coroutines

import io.github.bigironcheems.kanal.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * Suspends until the next event of type [T] matching [predicate] is posted,
 * then resumes with that event.
 *
 * The handler is registered on subscription and unregistered immediately after
 * the first matching event is received, or when the coroutine is cancelled.
 *
 * ```kotlin
 * val packet = bus.nextEvent<PacketReceived>()
 * val packet = bus.nextEvent<PacketReceived> { it.bytes > 100 }
 * ```
 *
 * @param priority  Dispatch priority; defaults to [Priority.NORMAL].
 * @param predicate Filter applied to incoming events; defaults to always true.
 */
public suspend inline fun <reified T : Event> EventBus.nextEvent(
    priority: Int = Priority.NORMAL,
    crossinline predicate: (T) -> Boolean = { true }
): T = suspendCancellableCoroutine { cont ->
    val sub = subscribe<T>(priority) { event ->
        if (!predicate(event)) return@subscribe
        if (!cont.isActive) return@subscribe
        cont.resume(event)
    }
    cont.invokeOnCancellation { sub.cancel() }
}

/**
 * Suspends until the next event of type [T] matching [predicate] is posted,
 * or until [timeout] elapses, whichever comes first.
 *
 * Returns `null` if the timeout elapses before a matching event arrives.
 *
 * ```kotlin
 * val packet = bus.nextEventOrNull<PacketReceived>(5.seconds)
 * val packet = bus.nextEventOrNull<PacketReceived>(5.seconds) { it.bytes > 100 }
 * ```
 *
 * @param timeout   Maximum duration to wait for a matching event.
 * @param priority  Dispatch priority; defaults to [Priority.NORMAL].
 * @param predicate Filter applied to incoming events; defaults to always true.
 */
public suspend inline fun <reified T : Event> EventBus.nextEventOrNull(
    timeout: Duration,
    priority: Int = Priority.NORMAL,
    crossinline predicate: (T) -> Boolean = { true }
): T? = withTimeoutOrNull(timeout) { nextEvent<T>(priority, predicate) }

/**
 * Suspends until the next event of type [T] matching [predicate] is posted
 * on this [TypedEventBus], then resumes with that event.
 *
 * @param priority  Dispatch priority; defaults to [Priority.NORMAL].
 * @param predicate Filter applied to incoming events; defaults to always true.
 */
public suspend inline fun <reified T : Event> TypedEventBus<in T>.nextEvent(
    priority: Int = Priority.NORMAL,
    crossinline predicate: (T) -> Boolean = { true }
): T = delegate.nextEvent<T>(priority, predicate)

/**
 * Suspends until the next event of type [T] matching [predicate] is posted
 * on this [TypedEventBus], or until [timeout] elapses.
 *
 * Returns `null` if the timeout elapses before a matching event arrives.
 *
 * @param timeout   Maximum duration to wait for a matching event.
 * @param priority  Dispatch priority; defaults to [Priority.NORMAL].
 * @param predicate Filter applied to incoming events; defaults to always true.
 */
public suspend inline fun <reified T : Event> TypedEventBus<in T>.nextEventOrNull(
    timeout: Duration,
    priority: Int = Priority.NORMAL,
    crossinline predicate: (T) -> Boolean = { true }
): T? = delegate.nextEventOrNull<T>(timeout, priority, predicate)
