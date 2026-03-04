package io.github.bigironcheems.kanal.internal

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Subscription
import io.github.bigironcheems.kanal.TypedEventBus
import java.util.concurrent.CompletableFuture

/**
 * Internal [TypedEventBus] implementation backed by a plain [EventBus].
 * All operations delegate directly; the type constraint [E] is enforced by the compiler
 * at call sites and erased at runtime.
 *
 * Not part of the public API; obtain instances via `EventBus.typed()`.
 */
internal class TypedEventBusAdapter<E : Event>(
    override val delegate: EventBus,
) : TypedEventBus<E> {

    override fun <T : E> post(event: T): T = delegate.post(event)

    override fun <T : E> postAsync(event: T): CompletableFuture<T> = delegate.postAsync(event)

    override fun subscribe(subscriber: Any) = delegate.subscribe(subscriber)

    override fun unsubscribe(subscriber: Any) = delegate.unsubscribe(subscriber)

    // The underlying bus stores handlers as (Event)->Unit; the cast to T is safe because
    // the bus only invokes this handler for events whose runtime type is exactly Class<T>.
    @Suppress("UNCHECKED_CAST")
    override fun <T : E> subscribe(
        eventClass: Class<T>,
        priority: Int,
        async: Boolean,
        handler: (T) -> Unit,
    ): Subscription = delegate.subscribe(eventClass, priority, async) { e -> handler(e as T) }

    override fun subscribeAll(priority: Int, handler: (Event) -> Unit): Subscription =
        delegate.subscribeAll(priority, handler)

    override fun isListening(eventClass: Class<out Event>): Boolean = delegate.isListening(eventClass)
}
