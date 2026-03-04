@file:JvmName("TypedEventBusFactory")

package io.github.bigironcheems.kanal

import io.github.bigironcheems.kanal.internal.TypedEventBusAdapter
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * Type-safe view over an [EventBus] that restricts posting and subscribing to subtypes of [E].
 *
 * Kotlin (reified):
 * ```kotlin
 * sealed interface NetworkEvent : Event
 * class PacketReceived(val bytes: ByteArray) : NetworkEvent
 *
 * val networkBus = EventBus().typed<NetworkEvent>()
 * networkBus.post(PacketReceived(bytes))   // OK
 * // networkBus.post(ButtonClickEvent())   // compile error
 * ```
 *
 * Java (class token):
 * ```java
 * TypedEventBus<NetworkEvent> networkBus =
 *     TypedEventBusFactory.typed(underlying, NetworkEvent.class);
 * ```
 *
 * The compile-time guarantee is strongest when [E] is a `sealed interface` or `sealed class`;
 * no external type can satisfy `T : E`, so the constraint cannot be bypassed from outside the module.
 *
 * `TypedEventBus<E>` is a thin adapter with zero runtime overhead beyond a single delegation.
 * The underlying bus is accessible via [delegate] for operations outside this interface
 * (e.g. [EventBus.subscribeStatic], [EventBus.unsubscribeAll]).
 * Handlers registered on either the typed view or the underlying bus are visible to both.
 *
 * Note: [subscribeAll] accepts `(Event) -> Unit` rather than `(E) -> Unit` because wildcard
 * handlers observe the raw underlying bus and may see events outside [E] if the bus is shared
 * between multiple typed views.
 *
 * @param E The root event type this bus accepts.
 */
public interface TypedEventBus<E : Event> {

    /** The underlying untyped [EventBus] backing this typed view. */
    public val delegate: EventBus

    /**
     * Dispatches [event] to all registered handlers in descending priority order and returns it.
     *
     * Delegates to [EventBus.post]; see that method for the full contract including
     * cancellation, async blocking behaviour, and executor-rejection handling.
     *
     * @return the same [event] instance.
     */
    public fun <T : E> post(event: T): T

    /**
     * Dispatches [event] asynchronously and returns a [CompletableFuture] that completes with
     * the event once all handlers have finished.
     *
     * Delegates to [EventBus.postAsync]; see that method for the full contract including
     * async ordering, cancellation thread-safety, and error handling.
     *
     * @return a [CompletableFuture] completing with [event] after all handlers finish.
     */
    public fun <T : E> postAsync(event: T): CompletableFuture<T>

    /**
     * Registers all instance methods on [subscriber] annotated with [@Subscribe][Subscribe].
     *
     * Methods handling event types outside [E] are silently ignored at dispatch time.
     * Calling this with the same object more than once is idempotent.
     */
    public fun subscribe(subscriber: Any)

    /**
     * Removes all instance handlers previously registered for [subscriber].
     */
    public fun unsubscribe(subscriber: Any)

    /**
     * Registers all static methods on [klass] annotated with [@Subscribe][Subscribe].
     *
     * Methods handling event types outside [E] are silently ignored at dispatch time.
     * See [EventBus.subscribeStatic] for details on static vs instance methods.
     */
    public fun subscribeStatic(klass: Class<*>): Unit = delegate.subscribeStatic(klass)

    /**
     * Removes all static handlers previously registered for [klass].
     */
    public fun unsubscribeStatic(klass: Class<*>): Unit = delegate.unsubscribeStatic(klass)

    /**
     * Registers a lambda handler for event type [T] and returns a [Subscription] token.
     *
     * Kotlin callers should prefer the reified inline overload:
     * ```kotlin
     * val sub = networkBus.subscribe<PacketReceived>(async = true) { e -> handle(e) }
     * ```
     *
     * @param async If `true`, dispatches on the bus's executor; falls back to sync if none configured.
     * @return a [Subscription] whose [Subscription.cancel] removes this handler.
     */
    @JvmSynthetic
    public fun <T : E> subscribe(
        eventClass: Class<T>,
        priority: Int,
        async: Boolean,
        handler: (T) -> Unit,
    ): Subscription

    /**
     * Registers a lambda handler for event type [T] with synchronous dispatch.
     *
     * Preserves binary compatibility with callers that predate the `async` parameter.
     *
     * @return a [Subscription] whose [Subscription.cancel] removes this handler.
     */
    @JvmSynthetic
    public fun <T : E> subscribe(
        eventClass: Class<T>,
        priority: Int,
        handler: (T) -> Unit,
    ): Subscription = subscribe(eventClass, priority, false, handler)

    /**
     * Registers a [Consumer] handler for event type [T] and returns a [Subscription] token.
     *
     * Java callers use plain void lambdas:
     * ```java
     * networkBus.subscribe(PacketReceived.class, Priority.NORMAL, true, e -> handle(e.bytes));
     * ```
     *
     * @param async If `true`, dispatches on the bus's executor; falls back to sync if none configured.
     * @return a [Subscription] whose [Subscription.cancel] removes this handler.
     */
    public fun <T : E> subscribe(
        eventClass: Class<T>,
        priority: Int,
        async: Boolean,
        handler: Consumer<T>,
    ): Subscription = subscribe(eventClass, priority, async) { e -> handler.accept(e) }

    /**
     * Registers a [Consumer] handler for event type [T] with synchronous dispatch.
     *
     * Preserves binary compatibility with callers that predate the `async` parameter.
     *
     * @return a [Subscription] whose [Subscription.cancel] removes this handler.
     */
    public fun <T : E> subscribe(
        eventClass: Class<T>,
        priority: Int,
        handler: Consumer<T>,
    ): Subscription = subscribe(eventClass, priority, false) { e -> handler.accept(e) }

    /**
     * Registers a wildcard handler that fires for every event posted to the underlying bus.
     *
     * Wildcard handlers are merged into the same priority-sorted dispatch list as typed handlers.
     * Wildcard handlers are always synchronous. When a wildcard falls after an async handler in
     * the dispatch chain it runs on whichever thread completed the prior async step, not the
     * posting thread. Code that relies on thread-local state (e.g. MDC logging context) will
     * not see the posting thread's context and should not be used in a wildcard handler on a
     * bus with async handlers.
     *
     * Kotlin callers should prefer the [subscribeAll] extension which defaults priority to [Priority.NORMAL].
     *
     * @return a [Subscription] whose [Subscription.cancel] removes this handler.
     */
    @JvmSynthetic
    public fun subscribeAll(priority: Int, handler: (Event) -> Unit): Subscription

    /**
     * Registers a wildcard [Consumer] handler that fires for every event posted to the underlying bus.
     *
     * @return a [Subscription] whose [Subscription.cancel] removes this handler.
     */
    public fun subscribeAll(
        priority: Int,
        handler: Consumer<Event>,
    ): Subscription = subscribeAll(priority) { e -> handler.accept(e) }

    /**
     * Returns `true` if at least one handler is registered for [eventClass] or any of its supertypes.
     */
    public fun isListening(eventClass: Class<out Event>): Boolean
}

//  Factory

/**
 * Returns a [TypedEventBus] view over this bus restricted to subtypes of [E].
 *
 * The returned bus shares the underlying handler registry; handlers registered on either
 * the typed view or the original bus are visible to both.
 *
 * Java callers pass a class token for type inference:
 * ```java
 * TypedEventBus<NetworkEvent> bus =
 *     TypedEventBusFactory.typed(underlying, NetworkEvent.class);
 * ```
 *
 * @param rootClass Unused at runtime (erased); present only to satisfy Java's type inference.
 */
@Suppress("UNUSED_PARAMETER")
public fun <E : Event> EventBus.typed(rootClass: Class<E>): TypedEventBus<E> =
    TypedEventBusAdapter(this)

/**
 * Returns a [TypedEventBus] view over this bus restricted to subtypes of [E].
 *
 * The returned bus shares the underlying handler registry; handlers registered on either
 * the typed view or the original bus are visible to both.
 *
 * ```kotlin
 * val networkBus = EventBus().typed<NetworkEvent>()
 * networkBus.post(PacketReceived(bytes))
 * ```
 */
public inline fun <reified E : Event> EventBus.typed(): TypedEventBus<E> =
    typed(E::class.java)

//  Kotlin extension helpers

/**
 * Registers a lambda handler for event type [T] on this [TypedEventBus] and returns a [Subscription] token.
 *
 * ```kotlin
 * val sub = networkBus.subscribe<PacketReceived> { e -> handle(e) }
 * val sub2 = networkBus.subscribe<ConnectionLost>(Priority.HIGH) { reconnect() }
 * val sub3 = networkBus.subscribe<PacketReceived>(async = true) { e -> handle(e) }
 * ```
 *
 * @param async If `true`, dispatches on the bus's executor; falls back to sync if none configured.
 * @return a [Subscription] whose [Subscription.cancel] removes this handler.
 */
public inline fun <reified T : Event> TypedEventBus<in T>.subscribe(
    priority: Int = Priority.NORMAL,
    async: Boolean = false,
    noinline handler: (T) -> Unit,
): Subscription = subscribe(T::class.java, priority, async, handler)

/**
 * Registers a wildcard handler on this [TypedEventBus] with [Priority.NORMAL] as the default priority.
 *
 * ```kotlin
 * val sub = networkBus.subscribeAll { e -> log(e) }
 * val sub2 = networkBus.subscribeAll(Priority.HIGH) { e -> audit(e) }
 * ```
 *
 * @return a [Subscription] whose [Subscription.cancel] removes this handler.
 */
public fun TypedEventBus<*>.subscribeAll(
    priority: Int = Priority.NORMAL,
    handler: (Event) -> Unit,
): Subscription = subscribeAll(priority, handler)

/**
 * Returns `true` if at least one handler is registered for [T] or any of its supertypes.
 *
 * ```kotlin
 * if (networkBus.isListening<PacketReceived>()) { ... }
 * ```
 */
public inline fun <reified T : Event> TypedEventBus<*>.isListening(): Boolean =
    isListening(T::class.java)
