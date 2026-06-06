package io.github.bigironcheems.kanal.coroutines

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Dispatches [event] on [context] and suspends until all handlers have finished.
 *
 * Delegates to [EventBus.post] on the specified coroutine context. When called
 * with no [context] argument, dispatch runs on the caller's current coroutine
 * context with no thread hop. Pass an explicit [context] to shift dispatch to
 * a specific dispatcher:
 *
 * ```kotlin
 * launch(Dispatchers.IO) {
 *     bus.postSuspend(PlayerJumpEvent("Steve"))                    // stays on IO
 *     bus.postSuspend(PlayerJumpEvent("Steve"), Dispatchers.Default) // shifts to Default
 * }
 * ```
 *
 * For most cases [EventBus.post] is sufficient. [postSuspend] is useful when
 * you need explicit dispatcher control over where dispatch runs.
 *
 * @param context The coroutine context to dispatch on; defaults to the caller's context.
 */
public suspend fun <T : Event> EventBus.postSuspend(
    event: T,
    context: CoroutineContext = EmptyCoroutineContext
): T = withContext(context) { post(event) }
