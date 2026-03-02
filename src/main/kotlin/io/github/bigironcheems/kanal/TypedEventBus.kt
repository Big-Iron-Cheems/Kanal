@file:JvmName("TypedEventBusFactory")

package io.github.bigironcheems.kanal

import io.github.bigironcheems.kanal.internal.TypedEventBusAdapter

/**
 * A type-safe view over an [EventBus] that restricts [post] and [subscribe] to a specific
 * event hierarchy [E]. Provides compile-time enforcement that only subtypes of [E] can be
 * posted or subscribed to through this interface.
 *
 * ### Obtaining an instance
 *
 * Kotlin (reified):
 * ```kotlin
 * sealed interface NetworkEvent : Event
 * class PacketReceived(val bytes: ByteArray) : NetworkEvent
 * class ConnectionLost(val reason: String) : NetworkEvent
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
 * ### Sealed hierarchies
 *
 * The compile-time guarantee is strongest when [E] is a `sealed interface` or `sealed class`;
 * no external type can satisfy `T : E`, so the constraint cannot be circumvented from outside
 * the module.
 *
 * ### Wildcard listeners
 *
 * [subscribeAll] accepts `(Event) -> Unit` rather than `(E) -> Unit` because wildcard
 * handlers observe the raw underlying bus and may see events outside this typed view if
 * the underlying [EventBus] is shared between multiple typed views.
 *
 * ### Underlying bus
 *
 * `TypedEventBus<E>` is a thin adapter with zero runtime overhead beyond a single delegation.
 * The underlying bus is accessible via [delegate] for operations not covered by this interface
 * (e.g. [EventBus.subscribeStatic], [EventBus.unsubscribeAll]).
 * Handlers registered on either the typed view or the underlying bus are visible to both.
 *
 * @param E The root event type this bus accepts. All posted events must be subtypes of [E].
 */
public interface TypedEventBus<E : Event> {

    /** The underlying untyped [EventBus] backing this typed view. */
    public val delegate: EventBus

    /**
     * Posts [event] to the bus and returns it after all handlers have been called.
     * The event type [T] is compile-time enforced to be a subtype of [E].
     */
    public fun <T : E> post(event: T): T

    /**
     * Registers all instance methods on [subscriber] annotated with [@Subscribe][Subscribe].
     *
     * Methods handling event types outside of the [E] hierarchy are silently ignored at
     * dispatch time; the underlying bus only invokes handlers whose registered type matches
     * the posted event.
     *
     * Use [unsubscribe] with the same instance to remove these handlers.
     */
    public fun subscribe(subscriber: Any)

    /**
     * Removes all handlers previously registered by [subscribe] for the given [subscriber].
     */
    public fun unsubscribe(subscriber: Any)

    /**
     * Registers all static methods on [klass] annotated with [@Subscribe][Subscribe].
     *
     * Methods handling event types outside of the [E] hierarchy are silently ignored at
     * dispatch time; the underlying bus only invokes handlers whose registered type matches
     * the posted event.
     *
     * See [EventBus.subscribeStatic] for details on static vs instance methods and the
     * Kotlin `object` caveat.
     *
     * Use [unsubscribeStatic] with the same class to remove these handlers.
     */
    public fun subscribeStatic(klass: Class<*>): Unit = delegate.subscribeStatic(klass)

    /**
     * Removes all static handlers previously registered for [klass].
     */
    public fun unsubscribeStatic(klass: Class<*>): Unit = delegate.unsubscribeStatic(klass)

    /**
     * Registers a lambda handler for event type [T] and returns a [Subscription] token.
     * Hidden from Java; Java callers use the [java.util.function.Consumer] overload.
     */
    @JvmSynthetic
    public fun <T : E> subscribe(
        eventClass: Class<T>,
        priority: Int,
        handler: (T) -> Unit,
    ): Subscription

    /**
     * Registers a [java.util.function.Consumer] handler for event type [T] and returns a
     * [Subscription] token for removal.
     *
     * Java callers use plain void lambdas:
     * ```java
     * networkBus.subscribe(PacketReceived.class, Priority.NORMAL, e -> handle(e.bytes));
     * ```
     * Kotlin callers should use the reified extension instead.
     */
    public fun <T : E> subscribe(
        eventClass: Class<T>,
        priority: Int,
        handler: java.util.function.Consumer<T>,
    ): Subscription = subscribe(eventClass, priority) { e -> handler.accept(e) }

    /**
     * Registers a wildcard handler that fires for every event posted to the underlying bus,
     * regardless of type. Returns a [Subscription] token for removal.
     * Hidden from Java; Java callers use the [java.util.function.Consumer] overload.
     */
    @JvmSynthetic
    public fun subscribeAll(priority: Int, handler: (Event) -> Unit): Subscription

    /**
     * Registers a wildcard [java.util.function.Consumer] handler that fires for every event
     * posted to the underlying bus. Returns a [Subscription] token for removal.
     *
     * Java callers must pass priority explicitly (no default on the interface).
     * Kotlin callers use the [subscribeAll] extension which defaults to [Priority.NORMAL].
     *
     * ```java
     * networkBus.subscribeAll(Priority.NORMAL, e -> log(e));
     * ```
     */
    public fun subscribeAll(
        priority: Int,
        handler: java.util.function.Consumer<Event>,
    ): Subscription = subscribeAll(priority) { e -> handler.accept(e) }

    /**
     * Returns `true` if at least one handler is registered for [eventClass] or any supertype.
     */
    public fun isListening(eventClass: Class<out Event>): Boolean
}

// ── Factory ───────────────────────────────────────────────────────────────────

/**
 * Java interop overload of [typed]. The [rootClass] token is unused at runtime
 * (generics are erased) but satisfies Java's type inference:
 * ```java
 * TypedEventBus<NetworkEvent> bus =
 *     TypedEventBusFactory.typed(underlying, NetworkEvent.class);
 * ```
 */
@Suppress("UNUSED_PARAMETER") // rootClass exists for Java type inference only; erased at runtime
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

// ── Kotlin extension helpers ──────────────────────────────────────────────────

/**
 * Registers a lambda handler for event type [T] on this [TypedEventBus].
 *
 * ```kotlin
 * val sub  = networkBus.subscribe<PacketReceived> { e -> handle(e) }
 * val sub2 = networkBus.subscribe<ConnectionLost>(Priority.HIGH) { reconnect() }
 * sub.cancel()
 * ```
 */
public inline fun <reified T : Event> TypedEventBus<in T>.subscribe(
    priority: Int = Priority.NORMAL,
    noinline handler: (T) -> Unit,
): Subscription = subscribe(T::class.java, priority, handler)

/**
 * Registers a wildcard handler on this [TypedEventBus] with [Priority.NORMAL] default.
 *
 * ```kotlin
 * val sub = networkBus.subscribeAll { e -> log(e) }
 * val sub2 = networkBus.subscribeAll(Priority.HIGH) { e -> audit(e) }
 * ```
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
