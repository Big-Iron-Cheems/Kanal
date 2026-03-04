package io.github.bigironcheems.kanal

import io.github.bigironcheems.kanal.internal.SimpleEventBus
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Central event dispatch interface.
 *
 * Obtain an instance via the companion factory:
 * ```kotlin
 * val bus = EventBus()
 * ```
 *
 * From Java:
 * ```java
 * EventBus bus = EventBus.create();
 * // With async dispatch:
 * EventBus bus = EventBus.create(Executors.newVirtualThreadPerTaskExecutor());
 * ```
 *
 * ### Subscribing
 * Pass any object whose methods are annotated with [@Subscribe][Subscribe]:
 * ```kotlin
 * bus.subscribe(myListener)
 * ```
 * Pass a class (or Kotlin object) to register `@JvmStatic` / companion-object handlers:
 * ```kotlin
 * bus.subscribeStatic(MyListener::class.java)
 * ```
 *
 * ### Posting
 * ```kotlin
 * bus.post(MyEvent())
 * ```
 * For [Cancellable] events the bus stops dispatching as soon as [Cancellable.isCancelled] is `true`.
 *
 * ### Error handling
 * By default, exceptions thrown inside handlers are printed to stderr. Supply a custom handler
 * at construction time via [EventBus.invoke]:
 * ```kotlin
 * val bus = EventBus { e -> logger.error("Handler error", e) }
 * ```
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
         * Handlers annotated with `@Subscribe(async = true)` or registered with `async = true`
         * will be dispatched on the given [asyncExecutor].
         *
         * **Recommended for JDK 21+:**
         * ```kotlin
         * val bus = EventBus(Executors.newVirtualThreadPerTaskExecutor())
         * ```
         *
         * @param asyncExecutor The executor used to run async handlers. Use `null` to disable async
         *                      (same as calling [invoke] with no arguments).
         */
        @JvmStatic
        @JvmName("create")
        public operator fun invoke(asyncExecutor: Executor?): EventBus =
            SimpleEventBus(asyncExecutor = asyncExecutor)

        /**
         * Creates a new [EventBus] with a custom error handler invoked whenever a handler throws.
         * Kotlin callers use this overload. Java callers use the
         * [java.util.function.Consumer] overload instead.
         *
         * @param exceptionHandler Called on the thread that called [post] if a handler throws.
         */
        @JvmSynthetic
        public operator fun invoke(exceptionHandler: (Throwable) -> Unit): EventBus =
            SimpleEventBus(exceptionHandler = exceptionHandler)

        /**
         * Creates a new [EventBus] with both a custom error handler and an async executor.
         *
         * @param asyncExecutor    The executor used to run async handlers.
         * @param exceptionHandler Called when a handler throws.
         */
        @JvmSynthetic
        public operator fun invoke(
            asyncExecutor: Executor?,
            exceptionHandler: (Throwable) -> Unit,
        ): EventBus = SimpleEventBus(asyncExecutor = asyncExecutor, exceptionHandler = exceptionHandler)

        /**
         * Java-friendly overload of [createWithHandler] accepting [java.util.function.Consumer]
         * so Java callers avoid [Unit] boilerplate:
         * ```java
         * EventBus bus = EventBus.createWithHandler(t -> System.err.println(t.getMessage()));
         * ```
         */
        @JvmStatic
        @JvmName("createWithHandler")
        public fun createWithHandler(exceptionHandler: java.util.function.Consumer<Throwable>): EventBus =
            SimpleEventBus { t -> exceptionHandler.accept(t) }

        /**
         * Java-friendly overload accepting both an async executor and a custom error handler:
         * ```java
         * EventBus bus = EventBus.createWithHandler(
         *     Executors.newVirtualThreadPerTaskExecutor(),
         *     t -> logger.error("Handler error", t)
         * );
         * ```
         */
        @JvmStatic
        @JvmName("createWithHandler")
        public fun createWithHandler(
            asyncExecutor: Executor?,
            exceptionHandler: java.util.function.Consumer<Throwable>,
        ): EventBus = SimpleEventBus(asyncExecutor = asyncExecutor) { t -> exceptionHandler.accept(t) }
    }

    /**
     * Registers all instance methods on [subscriber] annotated with [@Subscribe][Subscribe].
     *
     * Calling `subscribe` with the same object more than once is a no-op for handlers already
     * registered; duplicate subscriptions are not created.
     */
    public fun subscribe(subscriber: Any)

    /**
     * Registers all **static** methods on [klass] annotated with [@Subscribe][Subscribe].
     *
     * "Static" means:
     * - Java: `static` methods.
     * - Kotlin: methods annotated with `@JvmStatic` inside a `companion object` or `object`.
     *
     * **Kotlin `object` caveat:** plain (non-`@JvmStatic`) methods on a Kotlin `object` are
     * compiled as *instance* methods on the singleton, not static methods. They will **not** be
     * found by `subscribeStatic`. Either annotate them with `@JvmStatic`, or pass the singleton
     * instance to [subscribe] instead:
     *
     * ```kotlin
     * object MyListener {
     *     @JvmStatic @Subscribe          // found by subscribeStatic
     *     fun onA(e: EventA) { }
     *
     *     @Subscribe                     // found by subscribe(MyListener)
     *     fun onB(e: EventB) { }
     * }
     *
     * bus.subscribeStatic(MyListener::class.java)  // picks up onA
     * bus.subscribe(MyListener)                    // picks up onB
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
     * Dispatches [event] to all registered handlers whose parameter type matches [event]'s
     * runtime type **or any of its supertypes**, in descending priority order.
     *
     * If [event] implements [Cancellable], dispatch stops as soon as a handler sets
     * [Cancellable.isCancelled] to `true`.
     *
     * ### Async handlers and blocking
     *
     * If the bus was constructed with an [java.util.concurrent.Executor] and at least one
     * handler in the dispatch list has `async = true`, this method **blocks** the calling thread
     * until all handlers (sync and async) have completed. This preserves the existing contract
     * that `post` returns only after all handlers have run.
     *
     * This is safe with virtual-thread executors (`Executors.newVirtualThreadPerTaskExecutor()`).
     * With **bounded platform-thread pools**, a handler that re-entrantly calls `post` (or
     * `postAsync(...).join()`) on the same pool can deadlock if the pool is saturated.
     * Use [postAsync] in that case to get a non-blocking `CompletableFuture`.
     *
     * If no executor is configured, this method is entirely synchronous with no extra allocation.
     *
     * ### Infrastructure failures
     *
     * If the configured executor rejects the submitted work (e.g. it has been shut down),
     * [java.util.concurrent.CompletionException] propagates out of this method. This is an
     * infrastructure failure - not a handler error - and is intentionally not routed to
     * `exceptionHandler`. Handler exceptions are still swallowed into `exceptionHandler` and
     * never propagate here.
     *
     * @return the same [event] instance, for convenient one-liner posting.
     */
    public fun <T : Event> post(event: T): T

    /**
     * Dispatches [event] asynchronously, returning a [CompletableFuture] that completes
     * when **all** handlers (sync and async) have finished executing.
     *
     * Priority ordering and mutation visibility are preserved: handlers execute in the same
     * order as [post]. Async handlers are submitted to the bus's configured executor; sync
     * handlers that follow an async one are chained after the async step completes to ensure
     * they observe all mutations from higher-priority async handlers.
     *
     * **Error handling:** the future never completes exceptionally due to a handler error.
     * Exceptions are routed to the bus's `exceptionHandler` as in sync mode. The future
     * only completes exceptionally on infrastructure failure (e.g. executor rejected execution).
     *
     * If no executor is configured on this bus, all handlers run synchronously on the calling
     * thread and the returned future is already completed when this method returns.
     *
     * **Cancellation:** for [Cancellable] events, cancellation is automatically thread-safe.
     * No `@Volatile` annotation or `AtomicBoolean` is needed on the `isCancelled` field. The
     * bus wraps cancellation in an [java.util.concurrent.atomic.AtomicBoolean] for the duration
     * of the chain and writes the result back to the event once all handlers complete.
     *
     * @return a [CompletableFuture] completing with the event instance after all handlers finish.
     */
    public fun <T : Event> postAsync(event: T): CompletableFuture<T>

    /**
     * Kotlin-facing handler registration. Hidden from Java via [@JvmSynthetic]; Java callers
     * use the [java.util.function.Consumer] overload below.
     *
     * Kotlin callers should prefer the reified inline overload:
     * ```kotlin
     * val sub = bus.subscribe<MyEvent> { e -> ... }
     * val sub = bus.subscribe<MyEvent>(async = true) { e -> ... }
     * ```
     *
     * @param async If `true`, the handler is dispatched on the bus's configured executor.
     *              Falls back to synchronous execution if no executor is configured.
     */
    @JvmSynthetic
    public fun subscribe(eventClass: Class<out Event>, priority: Int, async: Boolean, handler: (Event) -> Unit): Subscription

    /**
     * Overload without `async` parameter; defaults to synchronous execution.
     * Preserves binary compatibility with existing callers.
     */
    @JvmSynthetic
    public fun subscribe(eventClass: Class<out Event>, priority: Int, handler: (Event) -> Unit): Subscription =
        subscribe(eventClass, priority, false, handler)

    /**
     * Registers a [java.util.function.Consumer] as a handler for [eventClass].
     *
     * Java callers use plain void lambdas:
     * ```java
     * Subscription sub = bus.subscribe(MyEvent.class, Priority.NORMAL, e -> handle(e));
     * // With async:
     * Subscription sub = bus.subscribe(MyEvent.class, Priority.NORMAL, true, e -> handle(e));
     * ```
     * Kotlin callers should use the reified inline overload instead.
     *
     * @param async If `true`, the handler is dispatched on the bus's configured executor.
     */
    public fun <T : Event> subscribe(
        eventClass: Class<T>,
        priority: Int,
        async: Boolean,
        handler: java.util.function.Consumer<T>,
    ): Subscription = subscribe(eventClass, priority, async) { e ->
        @Suppress("UNCHECKED_CAST")
        handler.accept(e as T)
    }

    /**
     * Registers a [java.util.function.Consumer] as a handler for [eventClass].
     * Uses synchronous dispatch (no async). Preserves binary compatibility.
     */
    public fun <T : Event> subscribe(
        eventClass: Class<T>,
        priority: Int,
        handler: java.util.function.Consumer<T>,
    ): Subscription = subscribe(eventClass, priority, false) { e ->
        @Suppress("UNCHECKED_CAST")
        handler.accept(e as T)
    }

    /**
     * Removes all handlers from this bus; both instance, static, lambda-based, and wildcard.
     * Useful for teardown in tests or on world unload.
     */
    public fun unsubscribeAll()

    /**
     * Registers a wildcard handler that fires for **every** event posted to this bus,
     * regardless of type. Returns a [Subscription] token for removal.
     *
     * Wildcard handlers are merged into the same priority-sorted dispatch list as typed
     * handlers. A wildcard at [Priority.HIGHEST] fires before typed handlers at
     * [Priority.NORMAL]; a wildcard at [Priority.LOWEST] fires after. Cancellation
     * mid-list stops both wildcard and typed handlers uniformly.
     *
     * Kotlin:
     * ```kotlin
     * val sub = bus.subscribeAll(Priority.HIGHEST) { e -> validate(e) }
     * val log = bus.subscribeAll(Priority.LOWEST) { e -> logger.debug("{}", e) }
     * sub.cancel()
     * ```
     *
     * Java:
     * ```java
     * Subscription sub = bus.subscribeAll(Priority.NORMAL, e -> log(e));
     * sub.cancel();
     * ```
     *
     * @param priority Dispatch priority; interleaves with typed handlers.
     * @param handler  Lambda receiving every posted event as [Event].
     * @return A [Subscription] whose [Subscription.cancel] removes this wildcard handler.
     */
    @JvmSynthetic
    public fun subscribeAll(priority: Int, handler: (Event) -> Unit): Subscription

    /**
     * Java-facing wildcard handler registration. Kotlin callers use the [subscribeAll]
     * extension which provides a default [Priority.NORMAL] value.
     *
     * ```java
     * bus.subscribeAll(Priority.NORMAL, e -> log(e));
     * ```
     */
    public fun subscribeAll(
        priority: Int,
        handler: java.util.function.Consumer<Event>,
    ): Subscription = subscribeAll(priority) { e -> handler.accept(e) }

    /**
     * Returns `true` if at least one wildcard handler is currently registered.
     */
    public fun isListeningAll(): Boolean

    /**
     * Returns `true` if at least one handler is currently registered for the given event type
     * or any of its supertypes.
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
 * sub.cancel()
 * ```
 *
 * @param async If `true`, the handler is dispatched on the bus's configured executor.
 *              Falls back to synchronous execution if no executor is configured.
 */
@Suppress("UNCHECKED_CAST")
public inline fun <reified T : Event> EventBus.subscribe(
    priority: Int = Priority.NORMAL,
    async: Boolean = false,
    noinline handler: (T) -> Unit,
): Subscription = subscribe(T::class.java, priority, async) { e -> handler(e as T) }

/**
 * Registers a wildcard handler with [Priority.NORMAL] default.
 *
 * ```kotlin
 * val sub = bus.subscribeAll { e -> println(e::class.simpleName) }
 * val sub2 = bus.subscribeAll(Priority.HIGH) { e -> audit(e) }
 * ```
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
