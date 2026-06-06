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
 * ### When to use asFlow vs suspendHandler
 * Use [asFlow] when you need Flow operators (filter, map, take, merge, etc.).
 * Use [suspendHandler] when you only need to handle each event with suspend logic
 * - it registers synchronously and has no subscription timing concerns.
 *
 * ### Subscription timing
 * The subscription is registered inside the collector coroutine body. In a tight
 * [kotlinx.coroutines.runBlocking] + [kotlinx.coroutines.launch] context, use
 * [kotlinx.coroutines.Dispatchers.Unconfined] when launching the collector to
 * guarantee registration before posting:
 *
 * ```kotlin
 * val job = launch(Dispatchers.Unconfined) {
 *     bus.asFlow<MyEvent>().take(N).collect { e -> handle(e) }
 * }
 * bus.post(MyEvent()) // safe - subscription guaranteed registered
 * job.join()
 * ```
 *
 * In a real coroutine scope (viewModelScope, lifecycleScope, etc.) the collector
 * is already running when you post, so no special dispatcher is needed.
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
 * networkBus.asFlow<PacketReceived>().collect { e -> handle(e) }
 * ```
 *
 * @param priority Dispatch priority; defaults to [Priority.NORMAL].
 */
public inline fun <reified T : Event> TypedEventBus<in T>.asFlow(
    priority: Int = Priority.NORMAL
): Flow<T> = delegate.asFlow<T>(priority)
