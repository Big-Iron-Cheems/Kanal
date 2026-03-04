package io.github.bigironcheems.kanal.internal

import io.github.bigironcheems.kanal.Cancellable
import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Subscribe
import io.github.bigironcheems.kanal.Subscription
import java.lang.invoke.LambdaMetafactory
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.function.Consumer

/**
 * Full [EventBus] implementation.
 *
 * ## Dispatch mechanism; LambdaMetafactory with privateLookupIn
 *
 * On JDK 9+ (including JDK 25) [MethodHandles.privateLookupIn] grants a [MethodHandles.Lookup]
 * with *private* access to the target class without requiring `--add-opens`. It succeeds whenever:
 *
 * - The JVM is running without the module system (unnamed module; classpath), **or**
 * - The subscriber's module explicitly `opens` its package to this library's module.
 *
 * If `privateLookupIn` throws [IllegalAccessException] (sealed / strongly encapsulated module),
 * we fall back to a plain [Method.invoke] lambda. The hot path after registration is always a
 * direct `Consumer.accept` call regardless of which path was taken.
 *
 * ## Data structures
 *
 * - `listeners: HashMap<Class<*>, CopyOnWriteArrayList<ListenerEntry>>`; one sorted list per
 *   *registered* event type. COWAL is safe for the concurrent read (post) / occasional write
 *   (subscribe/unsubscribe) pattern. Entries are kept in descending priority order.
 *
 * - `wildcardListeners: CopyOnWriteArrayList<ListenerEntry>`; handlers registered via
 *   [subscribeAll] that fire for **every** posted event regardless of type. Merged into
 *   [buildDispatchList] alongside typed entries and sorted by priority; so a wildcard at
 *   `Priority.HIGHEST` genuinely fires before typed handlers at `Priority.NORMAL`, and
 *   cancellation mid-list stops both wildcard and typed handlers the same way.
 *   Adding/removing a wildcard invalidates [dispatchCache] (same as any other mutation).
 *
 * - `dispatchCache: ConcurrentHashMap<Class<*>, List<ListenerEntry>>`; keyed by the *concrete*
 *   runtime class of a posted event. On first post of a novel type the cache is populated by
 *   walking the full supertype hierarchy (BFS over superclasses + interfaces), merging all
 *   matching listener lists, and sorting by priority. Every subsequent post of the same type
 *   is a single `ConcurrentHashMap.get` followed by a list iteration; no BFS, no sort.
 *   The cache is fully invalidated on any subscribe/unsubscribe (which is rare).
 *
 * - `invokerFactoryCache: ConcurrentHashMap<Method, (Any?) -> (Any?, Event) -> Unit>`; shared
 *   across all bus instances. [LambdaMetafactory] is paid at most once per [Method] per JVM
 *   lifetime. For instance methods the factory is then called per-subscriber to bind the receiver.
 *
 * - `registeredPairs: HashSet<Pair<Int, Method>>`; tracks `(identityHashCode, method)` pairs
 *   to make [subscribe]/[subscribeStatic] idempotent without preventing multiple distinct
 *   instances of the same class from each registering their own handlers.
 *
 * ## Subscription registration paths
 *
 * | API | Mechanism | Idempotency |
 * |-----|-----------|-------------|
 * | `subscribe(obj)` | reflect scan for `@Subscribe` instance methods | via `registeredPairs` |
 * | `subscribeStatic(Class)` | reflect scan for `@Subscribe` static methods | via `registeredPairs` |
 * | `subscribe(Class, Int, handler)` | direct `ListenerEntry` insertion | returns `Subscription` token |
 * | `subscribeAll(Int, handler)` | direct insertion into `wildcardListeners` | returns `Subscription` token |
 *
 * ## Thread safety
 *
 * [post] is lock-free on the hot path (reads a `ConcurrentHashMap` snapshot then iterates COWAL).
 * [subscribe]/[unsubscribe] and all mutations are synchronized on [lock].
 */
internal class SimpleEventBus(
    private val asyncExecutor: Executor? = null,
    private val exceptionHandler: (Throwable) -> Unit = Throwable::printStackTrace
) : EventBus {

    companion object {
        /**
         * Shared cache of *unbound* invoker factories, keyed by [Method].
         *
         * - For **static** methods the factory ignores its argument and returns the same
         *   `(Any?, Event) -> Unit` on every call.
         * - For **instance** methods the factory accepts the subscriber instance and returns
         *   a `Consumer<Event>` with the receiver already captured; so every distinct
         *   subscriber gets its own bound consumer while paying the [LambdaMetafactory]
         *   cost only once per method per JVM lifetime.
         *
         * Stored as `(Any?) -> (Any?, Event) -> Unit`:
         *   - argument: subscriber instance (ignored for statics)
         *   - return:   the ready-to-call invoker lambda
         */
        private val invokerFactoryCache = ConcurrentHashMap<Method, (Any?) -> (Any?, Event) -> Unit>()
    }

    //  Internal types

    /**
     * A compiled (or reflection-fallback) entry for a single handler method.
     *
     * @param priority  Dispatch priority; higher fires first.
     * @param instance  The subscriber object, or `null` for static handlers.
     * @param owner     The class that declared the method (used for identity / removal).
     * @param async     If `true`, this handler should be dispatched on the bus's executor when available.
     * @param invoke    The actual call: `invoke(instance, event)`; instance may be ignored for statics.
     */
    private class ListenerEntry(
        val priority: Int,
        val instance: Any?,     // null for static handlers
        val owner: Class<*>,    // declaring class; used for subscribeStatic removal
        val async: Boolean = false,
        val invoke: (Any?, Event) -> Unit
    )

    //  State

    // Map from event Class -> sorted list of directly-registered listeners.
    private val listeners = HashMap<Class<*>, CopyOnWriteArrayList<ListenerEntry>>()

    /**
     * Wildcard listeners; fire for **every** posted event regardless of type.
     * Kept in descending priority order, same as typed listener lists.
     * Merged into every [buildDispatchList] result.
     */
    private val wildcardListeners = CopyOnWriteArrayList<ListenerEntry>()

    /**
     * Merged dispatch list cache, keyed by the *concrete* event class posted.
     *
     * On first `post` of a given concrete type we walk its entire supertype
     * hierarchy (superclasses + interfaces), merge all matching listener lists
     * into a single priority-sorted snapshot, and cache it here.
     *
     * The cache is **invalidated** (cleared) whenever any listener is added or
     * removed; subscribe/unsubscribe is rare, post is hot.
     */
    private val dispatchCache = ConcurrentHashMap<Class<*>, List<ListenerEntry>>()

    // Tracks (identityHashCode, method) pairs for idempotent subscribe.
    private val registeredPairs = HashSet<Pair<Int, Method>>()

    // Guards subscribe / unsubscribe mutations.
    private val lock = Any()

    //  EventBus

    override fun subscribe(subscriber: Any) {
        val methods = allDeclaredMethods(subscriber::class.java)
            .filter { isValidHandler(it, static = false) }
        synchronized(lock) {
            val id = System.identityHashCode(subscriber)
            var changed = false
            for (method in methods) {
                if (!registeredPairs.add(id to method)) continue
                register(method, subscriber)
                changed = true
            }
            if (changed) dispatchCache.clear()
        }
    }

    override fun subscribeStatic(klass: Class<*>) {
        val methods = allDeclaredMethods(klass)
            .filter { isValidHandler(it, static = true) }
        synchronized(lock) {
            var changed = false
            for (method in methods) {
                if (!registeredPairs.add(System.identityHashCode(klass) to method)) continue
                register(method, instance = null, ownerOverride = klass)
                changed = true
            }
            if (changed) dispatchCache.clear()
        }
    }

    override fun subscribe(
        eventClass: Class<out Event>, priority: Int, async: Boolean, handler: (Event) -> Unit
    ): Subscription {
        val entry = ListenerEntry(priority, handler, eventClass, async) { _, e -> handler(e) }
        synchronized(lock) {
            insertSorted(listeners.getOrPut(eventClass) { CopyOnWriteArrayList() }, entry)
            dispatchCache.clear()
        }
        return object : Subscription {
            override fun cancel() {
                synchronized(lock) {
                    listeners[eventClass]?.remove(entry)
                    dispatchCache.clear()
                }
            }
        }
    }

    override fun subscribeAll(priority: Int, handler: (Event) -> Unit): Subscription {
        val entry = ListenerEntry(priority, null, Event::class.java) { _, e -> handler(e) }
        synchronized(lock) {
            insertSorted(wildcardListeners, entry)
            dispatchCache.clear()
        }
        return object : Subscription {
            override fun cancel() {
                synchronized(lock) {
                    wildcardListeners.remove(entry)
                    dispatchCache.clear()
                }
            }
        }
    }

    override fun unsubscribe(subscriber: Any) {
        synchronized(lock) {
            val id = System.identityHashCode(subscriber)
            listeners.values.forEach { it.removeIf { e -> e.instance === subscriber } }
            registeredPairs.removeIf { it.first == id }
            dispatchCache.clear()
        }
    }

    override fun unsubscribeStatic(klass: Class<*>) {
        synchronized(lock) {
            val id = System.identityHashCode(klass)
            listeners.values.forEach { it.removeIf { e -> e.instance == null && e.owner == klass } }
            registeredPairs.removeIf { it.first == id }
            dispatchCache.clear()
        }
    }

    override fun unsubscribeAll() {
        synchronized(lock) {
            listeners.clear()
            wildcardListeners.clear()
            registeredPairs.clear()
            dispatchCache.clear()
        }
    }

    override fun <T : Event> post(event: T): T {
        val list = resolveDispatchList(event) ?: return event
        if (list.isEmpty()) return event

        val executor = asyncExecutor
        return if (executor != null && list.any { it.async }) {
            // One or more handlers are async: build a chained future and block until done.
            // NOTE: this makes `post` a blocking call when async handlers are present.
            // It is safe with virtual-thread executors but can deadlock on bounded platform-thread
            // pools if a handler re-entrantly posts another event on the same pool. See `postAsync`
            // for a non-blocking alternative.
            val guard = (event as? Cancellable)?.let { AsyncCancellableGuard(it) }
            buildDispatchChain(event, list, executor, guard).join()
            guard?.flush()
            event
        } else {
            // Pure sync path, no allocations.
            val cancellable = event as? Cancellable
            for (entry in list) {
                if (cancellable?.isCancelled == true) break
                invokeEntry(entry, event)
            }
            event
        }
    }

    override fun <T : Event> postAsync(event: T): CompletableFuture<T> {
        val list = resolveDispatchList(event)
        if (list.isNullOrEmpty()) {
            return CompletableFuture.completedFuture(event)
        }

        val executor = asyncExecutor
        return if (executor != null) {
            val guard = (event as? Cancellable)?.let { AsyncCancellableGuard(it) }
            buildDispatchChain(event, list, executor, guard).thenApply {
                guard?.flush()
                event
            }
        } else {
            // No executor configured: run synchronously then return completed future.
            val cancellable = event as? Cancellable
            for (entry in list) {
                if (cancellable?.isCancelled == true) break
                invokeEntry(entry, event)
            }
            CompletableFuture.completedFuture(event)
        }
    }

    /**
     * Resolves the dispatch list for the given event, returning `null` if no handlers exist.
     */
    private fun resolveDispatchList(event: Event): List<ListenerEntry>? {
        val concreteClass = event::class.java
        val cached = dispatchCache[concreteClass]
        return when {
            cached != null -> cached
            supertypes(concreteClass).any { listeners[it]?.isNotEmpty() == true }
                || wildcardListeners.isNotEmpty() ->
                dispatchCache.getOrPut(concreteClass) { buildDispatchList(concreteClass) }
            else -> null
        }
    }

    /**
     * Builds a chained [CompletableFuture] that dispatches all [entries] in priority order,
     * preserving mutation visibility between handlers. Async entries are submitted to [executor];
     * sync entries that follow an async one are chained via `thenApply` to ensure they observe
     * all mutations from higher-priority async handlers.
     *
     * Cancellation is checked via [guard] (an [AsyncCancellableGuard] wrapping the event's
     * [Cancellable] interface, if present). The guard's [AtomicBoolean] is the authoritative
     * in-flight cancellation flag, making cross-thread cancellation visibility automatic.
     * Users do not need `@Volatile` on their `isCancelled` field.
     *
     * Exceptions from handlers are routed to [exceptionHandler]. The future never completes
     * exceptionally due to handler errors; only infrastructure failures (e.g. executor rejection)
     * propagate exceptionally.
     *
     * @return A [CompletableFuture] that completes when all handlers have finished.
     *         After joining, call [AsyncCancellableGuard.flush] to write the final cancellation
     *         state back to the original event.
     */
    private fun <T : Event> buildDispatchChain(
        event: T,
        entries: List<ListenerEntry>,
        executor: Executor,
        guard: AsyncCancellableGuard?,
    ): CompletableFuture<Unit> {
        var chain: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
        var hasAsyncInChain = false

        for (entry in entries) {
            // This check is only effective for the leading sync prefix (before the first async
            // step has been submitted). Once hasAsyncInChain is true, no async step has actually
            // executed yet. Correctness for the async portion is handled inside chainAsync/chainSync.
            if (!hasAsyncInChain && guard?.isCancelled == true) break
            chain = when {
                entry.async -> chainAsync(chain, entry, event, guard, executor).also { hasAsyncInChain = true }
                hasAsyncInChain -> chainSync(chain, entry, event, guard)
                else -> { invokeEntry(entry, event); chain }
            }
        }
        return chain
    }

    /** Appends an async handler step to [chain], dispatched on [executor]. */
    private fun chainAsync(
        chain: CompletableFuture<Unit>,
        entry: ListenerEntry,
        event: Event,
        guard: AsyncCancellableGuard?,
        executor: Executor,
    ): CompletableFuture<Unit> = chain.thenApplyAsync({
        // Sync the atomic from the delegate under the happens-before edge provided by the
        // prior future's completion, then check before invoking.
        guard?.syncFromDelegate()
        if (guard?.isCancelled != true) invokeEntry(entry, event)
        Unit
    }, executor)

    /** Appends a sync handler step to [chain] (runs on the completing thread of the prior step). */
    private fun chainSync(
        chain: CompletableFuture<Unit>,
        entry: ListenerEntry,
        event: Event,
        guard: AsyncCancellableGuard?,
    ): CompletableFuture<Unit> = chain.thenApply {
        guard?.syncFromDelegate()
        if (guard?.isCancelled == true) return@thenApply Unit
        invokeEntry(entry, event)
        Unit
    }

    /** Invokes [entry] directly, routing any exception to [exceptionHandler]. */
    private fun invokeEntry(entry: ListenerEntry, event: Event) {
        try {
            entry.invoke(entry.instance, event)
        } catch (t: Throwable) {
            exceptionHandler(t)
        }
    }

    override fun isListening(eventClass: Class<out Event>): Boolean =
        supertypes(eventClass).any { listeners[it]?.isNotEmpty() == true }

    override fun isListeningAll(): Boolean = wildcardListeners.isNotEmpty()

    //  Dispatch list builder

    /**
     * Merges all listener lists for [eventClass] and every supertype (superclasses
     * + interfaces, excluding [Any]) into a single priority-sorted list.
     * Wildcard listeners from [wildcardListeners] are also merged in, so they
     * participate in the same priority ordering as typed handlers.
     *
     * Called at most once per concrete event type (result is cached in [dispatchCache]).
     */
    private fun buildDispatchList(eventClass: Class<*>): List<ListenerEntry> {
        val merged = mutableListOf<ListenerEntry>()
        for (type in supertypes(eventClass)) {
            listeners[type]?.let { merged.addAll(it) }
        }
        merged.addAll(wildcardListeners)
        // Stable sort: preserve registration order within equal priorities.
        merged.sortWith(compareByDescending { it.priority })
        return merged
    }

    /**
     * Returns the full supertype hierarchy of [klass] in breadth-first order,
     * including [klass] itself, all superclasses, and all implemented interfaces.
     * [Any] / [Object] is excluded.
     */
    private fun supertypes(klass: Class<*>): List<Class<*>> {
        val result = mutableListOf<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        val seen = HashSet<Class<*>>()
        queue.add(klass)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == Any::class.java || !seen.add(cur)) continue
            // Only include types that are subtypes of Event.
            if (Event::class.java.isAssignableFrom(cur)) result.add(cur)
            cur.superclass?.let { queue.add(it) }
            cur.interfaces.forEach { queue.add(it) }
        }
        return result
    }

    //  Registration helpers

    /**
     * Inserts [entry] into [list] at the correct position to maintain descending
     * priority order. Equal-priority entries are appended after existing ones,
     * preserving registration order within the same priority level.
     */
    private fun insertSorted(list: CopyOnWriteArrayList<ListenerEntry>, entry: ListenerEntry) {
        var insertAt = list.size
        for (i in list.indices) {
            if (entry.priority > list[i].priority) {
                insertAt = i; break
            }
        }
        list.add(insertAt, entry)
    }

    /**
     * Compiles a [ListenerEntry] for [method] and appends it to the appropriate
     * listener list in [listeners].
     *
     * The invoker factory is looked up (or built) from [invokerFactoryCache] so
     * [LambdaMetafactory] is called at most once per [Method] per JVM lifetime.
     * For instance methods the factory is then called with [instance] to bind the
     * receiver into a `Consumer<Event>`.
     *
     * @param method        The handler method to register.
     * @param instance      Subscriber object for instance methods; `null` for static.
     * @param ownerOverride If non-null, used as the [ListenerEntry.owner] instead of
     *                      the runtime class of [instance]. Needed for `subscribeStatic`
     *                      where there is no instance to derive the class from.
     */
    private fun register(method: Method, instance: Any?, ownerOverride: Class<*>? = null) {
        val eventType = method.parameterTypes[0]
        val annotation = method.getAnnotation(Subscribe::class.java)!!
        val priority = annotation.priority
        val async = annotation.async
        val owner = ownerOverride ?: instance!!::class.java
        val factory = invokerFactoryCache.getOrPut(method) { buildInvokerFactory(method) }
        val invoker = factory(instance)
        val entry = ListenerEntry(priority, instance, owner, async, invoker)
        insertSorted(listeners.getOrPut(eventType) { CopyOnWriteArrayList() }, entry)
    }

    //  LambdaMetafactory / reflection invoker

    /**
     * Builds an **unbound** invoker factory for [method] and returns it as a
     * `(instance: Any?) -> (Any?, Event) -> Unit`.
     *
     * ### Static methods
     * The returned factory ignores its argument and always returns the same
     * pre-built `(Any?, Event) -> Unit`; the receiver-less `Consumer<Event>`
     * produced by [LambdaMetafactory] with a zero-capture `invokedType`.
     *
     * ### Instance methods
     * The returned factory accepts the subscriber instance and calls
     * `site.target.invoke(instance)` to produce a *bound* `Consumer<Event>`.
     * Each distinct subscriber object therefore gets its own consumer while the
     * [LambdaMetafactory] call (the expensive part) is shared via the cache.
     *
     * ### Type parameters for [LambdaMetafactory]
     * - `samType     = (Object)void`; fully erased so the factory inserts a
     *   checkcast rather than requiring an exact type match at the call site.
     * - `invokedType = ()Consumer` (static) or `(DeclaringClass)Consumer` (instance).
     * - `implType    = handle.type()`; the concrete method signature.
     *
     * Falls back to [reflectionInvokerFactory] if [LambdaMetafactory] is
     * unavailable (e.g. strongly encapsulated module).
     */
    private fun buildInvokerFactory(method: Method): (Any?) -> (Any?, Event) -> Unit {
        val isStatic = Modifier.isStatic(method.modifiers)
        return try {
            val callerLookup = MethodHandles.lookup()
            val lookup = try {
                MethodHandles.privateLookupIn(method.declaringClass, callerLookup)
            } catch (_: IllegalAccessException) {
                return reflectionInvokerFactory(method)
            }
            val handle = lookup.unreflect(method)

            if (isStatic) {
                val samType = MethodType.methodType(Void.TYPE, Any::class.java)
                val invokedType = MethodType.methodType(Consumer::class.java)
                val implType = handle.type()
                val site = LambdaMetafactory.metafactory(lookup, "accept", invokedType, samType, handle, implType)

                @Suppress("UNCHECKED_CAST")
                val consumer = site.target.invoke() as Consumer<Event>
                val invoker: (Any?, Event) -> Unit = { _, e -> consumer.accept(e) }
                { _ -> invoker }
            } else {
                val declaringClass = method.declaringClass
                val samType = MethodType.methodType(Void.TYPE, Any::class.java)
                val invokedType = MethodType.methodType(Consumer::class.java, declaringClass)
                val implType = handle.type()
                val site = LambdaMetafactory.metafactory(lookup, "accept", invokedType, samType, handle, implType)
                val siteTarget = site.target
                { inst ->
                    @Suppress("UNCHECKED_CAST")
                    val consumer = siteTarget.invoke(inst) as Consumer<Event>
                    { _, e -> consumer.accept(e) }
                }
            }
        } catch (_: Throwable) {
            reflectionInvokerFactory(method)
        }
    }

    /**
     * Reflection-based fallback factory used when [LambdaMetafactory] is unavailable.
     *
     * Sets [Method.isAccessible] to `true` once at factory-build time so the
     * per-call overhead is just [Method.invoke] rather than an access check on
     * every dispatch. [InvocationTargetException] is unwrapped so the original
     * cause reaches the bus's [exceptionHandler], not the wrapper.
     *
     * Returns the same unbound `(Any?) -> (Any?, Event) -> Unit` shape as
     * [buildInvokerFactory] so callers treat both paths identically.
     */
    private fun reflectionInvokerFactory(method: Method): (Any?) -> (Any?, Event) -> Unit {
        method.isAccessible = true
        return if (Modifier.isStatic(method.modifiers)) {
            val invoker: (Any?, Event) -> Unit = { _, e ->
                try {
                    method.invoke(null, e)
                } catch (ite: InvocationTargetException) {
                    throw ite.cause ?: ite
                }
            }
            { _ -> invoker }
        } else {
            { inst ->
                { _, e ->
                    try {
                        method.invoke(inst, e)
                    } catch (ite: InvocationTargetException) {
                        throw ite.cause ?: ite
                    }
                }
            }
        }
    }

    //  Reflection helpers

    /**
     * Returns all declared methods from [klass] and its entire superclass chain,
     * stopping before [Any]. Synthetic and bridge methods are excluded to avoid
     * picking up Kotlin-generated accessor lambdas or Java bridge overrides.
     *
     * **Override deduplication:** if a subclass declares a method with the same name
     * and parameter types as a superclass method, only the subclass version is included.
     * This prevents double-dispatch when both the override and the original are
     * annotated with [@Subscribe][Subscribe] (or when the subclass overrides
     * without re-annotating but the base class has the annotation).
     *
     * Interfaces are intentionally **not** walked here; interface default methods
     * are not valid handler sites, and static `@JvmStatic` methods on Kotlin
     * companion objects are already visible on the companion's class itself.
     */
    private fun allDeclaredMethods(klass: Class<*>): List<Method> {
        val result = mutableListOf<Method>()
        // Tracks (name, parameterTypes) of methods already collected from a subclass,
        // so superclass methods that are overridden are not added again.
        val seen = HashSet<Pair<String, List<Class<*>>>>()
        var cursor: Class<*>? = klass
        while (cursor != null && cursor != Any::class.java) {
            for (m in cursor.declaredMethods) {
                if (m.isSynthetic || m.isBridge) continue
                val sig = m.name to m.parameterTypes.toList()
                if (seen.add(sig)) result.add(m)
                // If seen.add returns false, a subclass already has this signature; skip.
            }
            cursor = cursor.superclass
        }
        return result
    }

    /**
     * Returns `true` if [method] is a `@Subscribe`-annotated handler with the expected
     * staticness, a void return type, and exactly one [Event]-assignable parameter.
     */
    private fun isValidHandler(method: Method, static: Boolean): Boolean {
        if (!method.isAnnotationPresent(Subscribe::class.java)) return false
        if (Modifier.isStatic(method.modifiers) != static) return false
        if (method.returnType != Void.TYPE) return false
        if (method.parameterCount != 1) return false
        return Event::class.java.isAssignableFrom(method.parameterTypes[0])
    }
}
