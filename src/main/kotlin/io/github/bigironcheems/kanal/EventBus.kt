package io.github.bigironcheems.kanal

import io.github.bigironcheems.kanal.internal.SimpleEventBus
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.Consumer

/**
 * Central event dispatch interface.
 *
 * ```kotlin
 * val bus = EventBus()
 * bus.subscribe(MyListener())
 * bus.post(MyEvent())
 * ```
 *
 * Java:
 * ```java
 * EventBus bus = EventBus.create();
 * bus.subscribe(new MyListener());
 * bus.post(new MyEvent());
 * // With async dispatch:
 * EventBus bus = EventBus.create(Executors.newVirtualThreadPerTaskExecutor());
 * ```
 *
 * Handlers are registered via annotation scanning ([subscribe]) or lambda ([subscribe] overloads).
 * For [Cancellable] events, dispatch stops as soon as any handler sets [Cancellable.isCancelled]
 * to `true`. Exceptions thrown inside handlers are routed to the configured `exceptionHandler`
 * and never propagate out of [post] or [postAsync].
 */
public interface EventBus {

    public companion object {
        /**
         * Creates a new [EventBus] with the default error handler (`Throwable::printStackTrace`).
         */
        @JvmStatic
        @JvmName("create")
        public operator fun invoke(): EventBus = SimpleEventBus()

        /**
         * Creates a new [EventBus] with an async executor for async handler dispatch.
         *
         * Handlers annotated with `@Subscribe(async = true)` or registered with `async = true`
         * will be dispatched on [asyncExecutor]. Pass `null` to get the same result as [invoke]
         * with no arguments.
         *
         * @param asyncExecutor Executor for async handlers; `null` disables async dispatch.
         */
        @JvmStatic
        @JvmName("create")
        public operator fun invoke(asyncExecutor: Executor?): EventBus =
            SimpleEventBus(asyncExecutor = asyncExecutor)

        /**
         * Creates a new [EventBus] with a custom exception handler.
         *
         * Kotlin callers use this overload; Java callers use [createWithHandler].
         *
         * @param exceptionHandler Invoked on the posting thread whenever a handler throws.
         */
        @JvmSynthetic
        public operator fun invoke(exceptionHandler: (Throwable) -> Unit): EventBus =
            SimpleEventBus(exceptionHandler = exceptionHandler)

        /**
         * Creates a new [EventBus] with both an async executor and a custom exception handler.
         *
         * @param asyncExecutor    Executor for async handlers; `null` disables async dispatch.
         * @param exceptionHandler Invoked on the posting thread whenever a handler throws.
         */
        @JvmSynthetic
        public operator fun invoke(
            asyncExecutor: Executor?,
            exceptionHandler: (Throwable) -> Unit,
        ): EventBus = SimpleEventBus(asyncExecutor = asyncExecutor, exceptionHandler = exceptionHandler)

        /**
         * Creates a new [EventBus] with a custom exception handler.
         *
         * Java-facing overload accepting [Consumer] to avoid [Unit] boilerplate.
         * Kotlin callers use the `EventBus { t -> ... }` lambda overload instead.
         *
         * @param exceptionHandler Invoked whenever a handler throws.
         */
        @JvmStatic
        @JvmName("createWithHandler")
        public fun createWithHandler(exceptionHandler: Consumer<Throwable>): EventBus =
            SimpleEventBus { t -> exceptionHandler.accept(t) }

        /**
         * Creates a new [EventBus] with both an async executor and a custom exception handler.
         *
         * Java-facing overload accepting [Consumer].
         * Kotlin callers use the `EventBus(executor) { t -> ... }` lambda overload instead.
         *
         * @param asyncExecutor    Executor for async handlers; `null` disables async dispatch.
         * @param exceptionHandler Invoked whenever a handler throws.
         */
        @JvmStatic
        @JvmName("createWithHandler")
        public fun createWithHandler(
            asyncExecutor: Executor?,
            exceptionHandler: Consumer<Throwable>,
        ): EventBus = SimpleEventBus(asyncExecutor = asyncExecutor) { t -> exceptionHandler.accept(t) }
    }

    /**
     * Registers all instance methods on [subscriber] annotated with [@Subscribe][Subscribe].
     *
     * Calling this with the same object more than once is idempotent; already-registered
     * handlers are not duplicated.
     */
    public fun subscribe(subscriber: Any)

    /**
     * Registers all static methods on [klass] annotated with [@Subscribe][Subscribe].
     *
     * "Static" means Java `static` methods or Kotlin `@JvmStatic` methods inside a
     * `companion object` or `object`. Plain (non-`@JvmStatic`) methods on a Kotlin `object`
     * are compiled as instance methods and must be registered via [subscribe] instead.
     *
     * ```kotlin
     * object MyListener {
     *     @JvmStatic @Subscribe fun onA(e: EventA) { }  // subscribeStatic
     *     @Subscribe fun onB(e: EventB) { }             // subscribe(MyListener)
     * }
     * ```
     */
    public fun subscribeStatic(klass: Class<*>)

    /**
     * Removes all instance handlers previously registered for [subscriber].
     */
    public fun unsubscribe(subscriber: Any)

    /**
     * Removes all static handlers previously registered for [klass].
     */
    public fun unsubscribeStatic(klass: Class<*>)

    /**
     * Dispatches [event] to all registered handlers in descending priority order and returns it.
     *
     * If [event] implements [Cancellable], dispatch stops as soon as any handler sets
     * [Cancellable.isCancelled] to `true`. Handler exceptions are routed to `exceptionHandler`
     * and never propagate to the caller.
     *
     * If the bus has an [Executor] configured and at least one handler in
     * the dispatch list has `async = true`, this method blocks the calling thread until all
     * handlers complete. This is safe with virtual-thread executors but can deadlock on bounded
     * platform-thread pools if a handler re-entrantly posts on the same pool. Use [postAsync]
     * to avoid blocking.
     *
     * If the executor rejects the submitted work (e.g. it has been shut down), the rejection is
     * routed to `exceptionHandler` rather than propagating to the caller. Use [postAsync] if you
     * need to distinguish executor rejection from handler errors at the call site.
     *
     * @return the same [event] instance.
     */
    public fun <T : Event> post(event: T): T

    /**
     * Dispatches [event] asynchronously and returns a [CompletableFuture] that completes with
     * the event once all handlers have finished.
     *
     * Handlers execute in the same priority order as [post]. Async handlers are submitted to
     * the bus's executor; sync handlers that follow an async one are chained after it completes
     * so they always observe mutations from higher-priority async handlers.
     *
     * Handler exceptions are routed to `exceptionHandler`; the future never completes
     * exceptionally due to handler errors. The future only completes exceptionally on
     * infrastructure failure (e.g. executor rejection). This is intentionally asymmetric
     * with [post], which routes infrastructure failures to `exceptionHandler` to preserve
     * its no-throw contract.
     *
     * If no executor is configured, all handlers run synchronously on the calling thread and
     * the returned future is already completed on return.
     *
     * For [Cancellable] events, cancellation is automatically thread-safe across async handlers.
     *
     * @return a [CompletableFuture] completing with [event] after all handlers finish.
     */
    public fun <T : Event> postAsync(event: T): CompletableFuture<T>

    /**
     * Registers a lambda handler for [eventClass] and returns a [Subscription] token.
     *
     * Kotlin callers should prefer the reified inline overload:
     * ```kotlin
     * val sub = bus.subscribe<MyEvent>(async = true) { e -> handle(e) }
     * ```
     *
     * @param async If `true`, dispatches on the bus's executor; falls back to sync if none configured.
     * @return a [Subscription] whose [Subscription.cancel] removes this handler.
     */
    @JvmSynthetic
    public fun subscribe(
        eventClass: Class<out Event>,
        priority: Int,
        async: Boolean,
        handler: (Event) -> Unit
    ): Subscription

    /**
     * Registers a lambda handler for [eventClass] with synchronous dispatch.
     *
     * Preserves binary compatibility with callers that predate the `async` parameter.
     *
     * @return a [Subscription] whose [Subscription.cancel] removes this handler.
     */
    @JvmSynthetic
    public fun subscribe(eventClass: Class<out Event>, priority: Int, handler: (Event) -> Unit): Subscription =
        subscribe(eventClass, priority, false, handler)

    /**
     * Registers a [Consumer] handler for [eventClass] and returns a [Subscription] token.
     *
     * Java callers use plain void lambdas:
     * ```java
     * Subscription sub = bus.subscribe(MyEvent.class, Priority.NORMAL, true, e -> handle(e));
     * ```
     *
     * @param async If `true`, dispatches on the bus's executor; falls back to sync if none configured.
     * @return a [Subscription] whose [Subscription.cancel] removes this handler.
     */
    public fun <T : Event> subscribe(
        eventClass: Class<T>,
        priority: Int,
        async: Boolean,
        handler: Consumer<T>,
    ): Subscription = subscribe(eventClass, priority, async) { e ->
        @Suppress("UNCHECKED_CAST")
        handler.accept(e as T)
    }

    /**
     * Registers a [Consumer] handler for [eventClass] with synchronous dispatch.
     *
     * Preserves binary compatibility with callers that predate the `async` parameter.
     *
     * @return a [Subscription] whose [Subscription.cancel] removes this handler.
     */
    public fun <T : Event> subscribe(
        eventClass: Class<T>,
        priority: Int,
        handler: Consumer<T>,
    ): Subscription = subscribe(eventClass, priority, false) { e ->
        @Suppress("UNCHECKED_CAST")
        handler.accept(e as T)
    }

    /**
     * Removes all registered handlers from this bus: instance, static, lambda, and wildcard.
     */
    public fun unsubscribeAll()

    /**
     * Registers a wildcard handler that fires for every posted event regardless of type.
     *
     * Wildcard handlers are merged into the same priority-sorted dispatch list as typed handlers,
     * so a wildcard at [Priority.HIGHEST] fires before typed handlers at [Priority.NORMAL].
     * Cancellation stops both wildcard and typed handlers uniformly.
     *
     * Wildcard handlers are always synchronous. When a wildcard falls after an async handler in
     * the dispatch chain it runs on whichever thread completed the prior async step, not the
     * posting thread. Code that relies on thread-local state (e.g. MDC logging context) will
     * not see the posting thread's context and should not be used in a wildcard handler on a
     * bus with async handlers.
     *
     * Kotlin callers should prefer the [subscribeAll] extension which defaults priority to [Priority.NORMAL].
     * Java callers use the [Consumer] overload.
     *
     * @return a [Subscription] whose [Subscription.cancel] removes this handler.
     */
    @JvmSynthetic
    public fun subscribeAll(priority: Int, handler: (Event) -> Unit): Subscription

    /**
     * Registers a wildcard [Consumer] handler that fires for every posted event.
     *
     * @return a [Subscription] whose [Subscription.cancel] removes this handler.
     */
    public fun subscribeAll(
        priority: Int,
        handler: Consumer<Event>,
    ): Subscription = subscribeAll(priority) { e -> handler.accept(e) }

    /**
     * Returns `true` if at least one wildcard handler is currently registered.
     */
    public fun isListeningAll(): Boolean

    /**
     * Returns `true` if at least one handler is registered for [eventClass] or any of its supertypes.
     */
    public fun isListening(eventClass: Class<out Event>): Boolean
}

//  Kotlin-only extension helpers

/**
 * Registers a lambda handler for event type [T] and returns a [Subscription] token.
 *
 * ```kotlin
 * val sub = bus.subscribe<PlayerJumpEvent> { e -> println(e.player) }
 * val sub2 = bus.subscribe<BlockBreakEvent>(Priority.HIGH) { e -> e.cancel() }
 * val sub3 = bus.subscribe<PacketReceived>(async = true) { e -> handle(e) }
 * ```
 *
 * @param async If `true`, dispatches on the bus's executor; falls back to sync if none configured.
 * @return a [Subscription] whose [Subscription.cancel] removes this handler.
 */
@Suppress("UNCHECKED_CAST")
public inline fun <reified T : Event> EventBus.subscribe(
    priority: Int = Priority.NORMAL,
    async: Boolean = false,
    noinline handler: (T) -> Unit,
): Subscription = subscribe(T::class.java, priority, async) { e -> handler(e as T) }

/**
 * Registers a wildcard handler with [Priority.NORMAL] as the default priority.
 *
 * ```kotlin
 * val sub = bus.subscribeAll { e -> println(e::class.simpleName) }
 * val sub2 = bus.subscribeAll(Priority.HIGH) { e -> audit(e) }
 * ```
 *
 * @return a [Subscription] whose [Subscription.cancel] removes this handler.
 */
public fun EventBus.subscribeAll(
    priority: Int = Priority.NORMAL,
    handler: (Event) -> Unit,
): Subscription = subscribeAll(priority, handler)

/**
 * Returns `true` if at least one handler is registered for [T] or any of its supertypes.
 *
 * ```kotlin
 * if (bus.isListening<PlayerJumpEvent>()) { ... }
 * ```
 */
public inline fun <reified T : Event> EventBus.isListening(): Boolean = isListening(T::class.java)
