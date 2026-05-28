package io.github.bigironcheems.kanal.coroutines

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Dispatches [event] on [context] and suspends until all handlers have finished.
 *
 * Delegates to [EventBus.post] on the specified coroutine context. Useful when
 * posting from a coroutine and you want to control which dispatcher the dispatch
 * runs on without blocking the calling coroutine's thread directly.
 *
 * ```kotlin
 * launch {
 *     bus.postSuspend(PlayerJumpEvent("Steve"))
 * }
 * ```
 *
 * @param context The coroutine context to dispatch on; defaults to [Dispatchers.Default].
 */
public suspend fun <T : Event> EventBus.postSuspend(
    event: T,
    context: CoroutineContext = Dispatchers.Default
): T = withContext(context) { post(event) }
