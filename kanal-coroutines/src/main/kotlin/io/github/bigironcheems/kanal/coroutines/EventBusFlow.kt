package io.github.bigironcheems.kanal.coroutines

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Priority
import io.github.bigironcheems.kanal.subscribe
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Returns a [Flow] that emits every event of type [T] posted to this [EventBus].
 *
 * The handler is registered when the flow is collected and unregistered when
 * the collector is cancelled, via [awaitClose].
 *
 * ```kotlin
 * bus.asFlow<PlayerJumpEvent>()
 *     .filter { it.player == "Steve" }
 *     .collect { e -> println(e.player) }
 * ```
 *
 * @param priority Dispatch priority; defaults to [Priority.NORMAL].
 */
public inline fun <reified T : Event> EventBus.asFlow(priority: Int = Priority.NORMAL): Flow<T> = callbackFlow {
    val sub = subscribe<T>(priority) { trySend(it).isSuccess }
    awaitClose { sub.cancel() }
}
