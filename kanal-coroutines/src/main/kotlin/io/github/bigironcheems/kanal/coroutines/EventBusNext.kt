package io.github.bigironcheems.kanal.coroutines

import io.github.bigironcheems.kanal.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * Suspends until the next event of type [T] matching [predicate] is posted,
 * then resumes with that event.
 *
 * The handler is registered synchronously inside the coroutine body before
 * the first suspension point. In a [kotlinx.coroutines.runBlocking] +
 * [kotlinx.coroutines.launch] context, use [kotlinx.coroutines.Dispatchers.Unconfined]
 * to ensure the coroutine body executes and registers the subscription before
 * any event is posted. In a real coroutine scope that is already running,
 * no special dispatcher is needed.
 *
 * ```kotlin
 * val packet = bus.nextEvent<PacketReceived>()
 * val packet = bus.nextEvent<PacketReceived> { it.bytes.size > 100 }
 * ```
 *
 * @param priority  Dispatch priority; defaults to [Priority.NORMAL].
 * @param predicate Filter applied to incoming events; defaults to always true.
 */
public suspend inline fun <reified T : Event> EventBus.nextEvent(
    priority: Int = Priority.NORMAL,
    crossinline predicate: (T) -> Boolean = { true }
): T = suspendCancellableCoroutine { cont ->
    val claimed = AtomicBoolean(false)
    val sub = subscribe<T>(priority) { event ->
        if (!predicate(event)) return@subscribe
        if (claimed.compareAndSet(false, true)) cont.resume(event)
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
 * val packet = bus.nextEventOrNull<PacketReceived>(5.seconds) { it.bytes.size > 100 }
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
