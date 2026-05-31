package io.github.bigironcheems.kanal.coroutines

import io.github.bigironcheems.kanal.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Returns a [Flow] that emits every event of type [T] posted to this [EventBus].
 *
 * The handler is registered when the flow is collected and unregistered when
 * the collector is cancelled, via [awaitClose].
 *
 * ### Subscription timing
 * The subscription is registered inside the collector coroutine body. In a concurrent
 * context, the subscription may not be active immediately after [kotlinx.coroutines.launch].
 * Use [kotlinx.coroutines.Dispatchers.Unconfined] when launching the collector to run
 * the coroutine eagerly on the current thread until the first suspension point, guaranteeing
 * the subscription is registered before any event is posted:
 *
 * ```kotlin
 * val job = launch(Dispatchers.Unconfined) {
 *     bus.asFlow<MyEvent>()
 *         .take(N)
 *         .collect { e -> handle(e) }
 * }
 * bus.post(MyEvent()) // safe — subscription guaranteed registered
 * job.join()
 * ```
 *
 * If you only need to handle events with suspend logic and don't need Flow operators,
 * prefer [suspendHandler] which registers synchronously and avoids this concern entirely.
 *
 * @param priority Dispatch priority; defaults to [Priority.NORMAL].
 */
public inline fun <reified T : Event> EventBus.asFlow(priority: Int = Priority.NORMAL): Flow<T> = callbackFlow {
    val sub = subscribe<T>(priority) { trySend(it).isSuccess }
    awaitClose { sub.cancel() }
}

/**
 * Returns a [Flow] that emits every event of type [T] posted to this [TypedEventBus].
 *
 * Delegates to [EventBus.asFlow] on the underlying bus.
 *
 * ```kotlin
 * val networkBus = EventBus().typed<NetworkEvent>()
 * networkBus.asFlow<PacketReceived>()
 *     .collect { e -> handle(e) }
 * ```
 *
 * @param priority Dispatch priority; defaults to [Priority.NORMAL].
 */
public inline fun <reified T : Event> TypedEventBus<in T>.asFlow(
    priority: Int = Priority.NORMAL
): Flow<T> = delegate.asFlow<T>(priority)
