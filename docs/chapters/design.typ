#import "../theme.typ": *

= Design Notes <design>

This chapter explains the decisions behind the implementation. It is for contributors
and for integrators who want to understand *why* the library behaves the way it does.

== Why LambdaMetafactory <design-lambdametafactory>

Handler dispatch on the hot path is a `Consumer<Event>.accept(event)` virtual call.
That consumer is created once at subscription time via `LambdaMetafactory`
@lambdametafactory.

The alternative is `Method.invoke`, which carries two costs on a hot dispatch path:
- Boxing of primitive arguments.
- Reflective access checks on every call, or a megamorphic virtual call through the
  reflection machinery @megamorphic.

`LambdaMetafactory` generates a hidden class that calls the target method directly.
After JIT inlining it is indistinguishable from a direct call. The cost is paid once
per method per JVM lifetime, cached in `invokerFactoryCache`. Subsequent subscriptions
of the same method from different instances pay only the factory call to bind the
receiver.

The fallback to `Method.invoke` exists for modules that are strongly encapsulated:
`privateLookupIn` @methodhandles-lookup throws `IllegalAccessException` for them. The
dispatch is slower but correct.

== Why CopyOnWriteArrayList per event type <design-cowal>

The per-type handler list uses `CopyOnWriteArrayList` @cowal because the access pattern
strongly favours reads:

- *Reads are frequent and must be lock-free.* `post` reads the list on every call.
  COWAL's snapshot is a plain array reference read with no lock.
- *Writes are rare.* `subscribe` and `unsubscribe` happen at startup or feature toggle
  time, not on the hot path.
- *Write cost is acceptable.* A COWAL write copies the array -- O(n) in handler count.
  Subscriber objects typically have 2-10 handlers; the copy is small.

A `ConcurrentLinkedQueue` or locked `ArrayList` would be cheaper to write but would
require a lock or volatile read on every `post`. COWAL shifts synchronisation cost
entirely to writes, leaving the read path allocation-free.

== Why dispatchCache <design-cache>

The first `post` of a given event type walks the full supertype hierarchy (BFS over
superclasses and interfaces), merges all applicable handler lists, and sorts by
priority. For a class implementing several interfaces with handlers at different
priorities this is non-trivial work.

`dispatchCache` is a `ConcurrentHashMap` @concurrenthashmap keyed by the concrete
runtime class. Every subsequent post of the same type is a single map lookup plus a
COWAL iteration -- no BFS, no merge, no sort.

The cache is fully invalidated on any `subscribe` or `unsubscribe` call. Partial
invalidation (evicting only entries affected by the changed handler) would be cheaper
but would add per-entry bookkeeping on the subscribe path. In practice, subscriptions
happen at startup; the cache is populated once and not invalidated again during
steady-state operation.

`DispatchList` pre-computes `hasAnyAsync` at build time. The hot path in `post` checks
a single boolean to decide whether to build a `CompletableFuture` chain. Buses with no
async handlers incur zero chain allocation (see @spec-sync-prefix).

== Registration identity: why IdentityHashMap <design-registration-identity>

`subscribe(obj)` and `subscribeStatic(Class)` must be idempotent per object (@r1):
registering the same instance twice must not duplicate its handlers, while two distinct
instances of the same class must each register independently. This requires a key that
uniquely identifies *the object itself*, not a value derived from it.

Already-registered methods are tracked in `IdentityHashMap<Any, MutableSet<Method>>`
(instance subscribers) and `IdentityHashMap<Class<*>, MutableSet<Method>>` (static
subscribers). Unlike `HashMap`, `IdentityHashMap` compares keys with `==` (reference
equality) rather than `equals()`/`hashCode()`. This matters because any hash-derived key
is a fixed-width value with no uniqueness guarantee; two distinct objects can legally
share one. A registration scheme keyed on such a hash could conflate two different
subscribers that collide, silently dropping one's registration or, on `unsubscribe`,
erasing the wrong object's bookkeeping entry while its handler remains live -- allowing
a later re-subscribe to register a duplicate handler for it.

`IdentityHashMap` keys on the reference itself, so two distinct objects can never be
conflated regardless of any hash value. `unsubscribe` and `unsubscribeStatic` remove
entries by direct key lookup on the same map, so removal is scoped exactly to the object
being unsubscribed with no possibility of cross-object interference.

== TypedEventBus as a zero-overhead adapter <design-typed>

`TypedEventBus<E>` is a thin interface backed by `TypedEventBusAdapter`, which holds a
reference to the underlying `EventBus` and delegates every call to it. There is no
additional data structure, no separate handler list, no secondary dispatch.

The compile-time constraint is enforced by the generic bound `T : E` on `post` and
`subscribe`. At runtime the adapter simply calls the underlying bus. The JIT inlines
the delegation call; the adapter disappears from the instruction stream.

Handlers registered via the typed view and handlers registered directly on the
underlying bus share the same dispatch list. The typed view is a call-site restriction,
not a separate bus.

== Async as a threading strategy layer, not a new dispatch model <design-async-model>

The design goal was to add async dispatch without forking the dispatch logic. The same
priority ordering, the same cancellation check, and the same mutation visibility
contract all had to work unchanged.

The implementation achieves this by building one `CompletableFuture` chain that
represents the entire dispatch in order. Async steps are `thenRunAsync`; sync steps
after an async step are `thenRun`. The chain is built eagerly before any step executes,
so order is determined synchronously on the posting thread. No step can overtake
another.

Parallel execution of handlers was never considered. It would violate @d3 and @d4 (see
@spec-dispatch) and would require either immutable events or explicit synchronisation on
every event field access. The model is strictly sequential; async only in the sense of
which thread each step runs on.

== Cancellation guard internals <design-cancellation-guard>

`AsyncCancellableGuard` wraps a `Cancellable` event during async dispatch. It holds an
`AtomicBoolean` for the duration of the chain.

At the start of each chain step, `syncFromDelegate()` reads `isCancelled` from the
original event under the happens-before edge provided by the prior future's completion,
then syncs that value into the atomic. This handles the case where a handler writes
directly to the event's field (not to the guard): the write is made visible by the
chain's happens-before edge before the next step reads it.

After the chain completes, `flush()` writes the final flag value back to the event's
`isCancelled` field, making the result visible to the posting thread.

This design means the event class is completely unaware of the guard. A plain
`var isCancelled = false` is sufficient. The user-visible contract is in
@spec-cancellable; the dangerous edge is in @edge-post-blocking.

== Why wildcard handlers have no async flag <design-wildcard>

Wildcards are designed for cross-cutting observers: logging, auditing, metrics. These
use cases are:

- Observational: they read the event; they do not mutate it.
- Cheap: emitting a log line is not worth executor overhead.
- Ubiquitous: a wildcard fires for every posted event.

Allowing an async flag on `subscribeAll` would add API surface (the parameter,
interaction documentation, tests for every combination) for a use case that is already
well-served by `postAsync(event).thenAccept { sink.write(it) }` at the call site. That
approach moves the async work outside the dispatch boundary entirely, which is cleaner.

The constraint is explicit in the API and is not an oversight. The thread-context
implication is documented as a dangerous edge in @edge-wildcard-thread.

== Error routing asymmetry between `post` and `postAsync` <design-error-asymmetry>

`post` routes executor rejection to `exceptionHandler` and never throws. Callers of
`post` are not in a position to handle a `CompletionException`; they expect the method
to return normally. Routing to `exceptionHandler` is the only available mechanism.

`postAsync` propagates rejection as an exceptional future completion. Callers who hold
a `CompletableFuture` are already equipped to handle exceptional states via
`exceptionally`, `whenComplete`, or `handle`. Silently routing infrastructure failure
to `exceptionHandler` when the caller explicitly asked for a future would hide
infrastructure problems from callers who want to observe them.

The asymmetry is intentional. The practical implication -- that discarding the
`postAsync` future can silently swallow executor rejection -- is a dangerous edge;
see @edge-error-asymmetry.

== `nextEvent` continuation safety <design-nextevent>

`nextEvent` uses `suspendCancellableCoroutine` @cancellablecontinuation. A naive
implementation guards the resume call with an `isActive` check:

```kotlin
// Unsafe: check and resume are not atomic
if (!cont.isActive) return@subscribe
cont.resume(event)
```

On a bus with concurrent posters, two matching events arriving on different threads can
both pass the `isActive` check before either calls `cont.resume`. The second call
throws `IllegalStateException`; `CancellableContinuation` does not permit concurrent
resumes.

The implementation uses an `AtomicBoolean` claimed flag:

```kotlin
val claimed = AtomicBoolean(false)
val sub = subscribe<T>(priority) { event ->
    if (!predicate(event)) return@subscribe
    if (claimed.compareAndSet(false, true)) cont.resume(event)
}
cont.invokeOnCancellation { sub.cancel() }
```

`compareAndSet(false, true)` is atomic. Exactly one thread wins. All subsequent
arrivals find `claimed` already true and return immediately. The formal guarantee
is @c2.

== `suspendHandler` scope ownership <design-suspendhandler-scope>

Early versions accepted `scope: CoroutineScope` with a default of
`CoroutineScope(Dispatchers.Default + SupervisorJob())`. The `cancel()` implementation
unconditionally called `scope.coroutineContext[Job]?.cancel()`.

This was a correctness bug. If a caller passed `viewModelScope`, calling `sub.cancel()`
would cancel the entire `viewModelScope`, destroying every coroutine tied to it -- not
just the ones launched by this handler.

The fix changes the parameter to `scope: CoroutineScope?`. A `null` value causes the
function to create an internal scope (`ownsScope = true`). A non-null value sets
`ownsScope = false`. The `cancel()` implementation only cancels the scope when
`ownsScope` is true:

```kotlin
override fun cancel() {
    sub.cancel()
    if (ownsScope) effectiveScope.coroutineContext[Job]?.cancel()
}
```

When the caller provides an explicit scope, `sub.cancel()` removes the subscription
from the bus but leaves the scope untouched. The formal contract is @c4; the
practical edge case is in @edge-suspendhandler-scope.
