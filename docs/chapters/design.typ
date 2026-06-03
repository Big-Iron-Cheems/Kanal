#import "../theme.typ": *

= Design Notes <design>

This chapter explains the decisions behind the implementation. It is intended for
contributors and for integrators who want to understand why the library behaves
the way it does, not just what it does.

== Why LambdaMetafactory <design-lambdametafactory>

Handler dispatch on the hot path is a `Consumer<Event>.accept(event)` virtual call.
That consumer is created once at subscription time via `LambdaMetafactory` @lambdametafactory.

The alternative is `Method.invoke`, which carries two costs that compound on a hot
dispatch path:
- Boxing of primitive arguments.
- Reflective access checks on every call (pre-JDK 9 style) or a megamorphic virtual
  call through the reflection machinery (post-JDK 9) @megamorphic.

`LambdaMetafactory` generates a hidden class that directly calls the target method.
After JIT inlining it is indistinguishable from a direct method call. The cost is
paid once per method per JVM lifetime (cached in `invokerFactoryCache`); subsequent
subscriptions of the same method from different instances pay only the factory call
to bind the receiver.

The fallback to `Method.invoke` exists for modules that are strongly encapsulated
(the `privateLookupIn` call @methodhandles-lookup throws `IllegalAccessException`). In that case the
dispatch is slower but still correct.

== Why CopyOnWriteArrayList per event type <design-cowal>

The choice of `CopyOnWriteArrayList` @cowal for the per-type handler list is driven by the
access pattern:

- *Reads are frequent and need no locking.* `post` reads the list on every call.
  With COWAL, the snapshot is a plain array reference read. No lock is acquired.
- *Writes are rare.* `subscribe` and `unsubscribe` are called once at startup or
  when a feature is toggled; not on the hot path.
- *Write cost is acceptable.* A COWAL write copies the array, which is O(n) in the
  number of handlers. Typical subscriber objects have 2-10 handlers. The copy is
  a small allocation even at `HIGHEST` priority.

A `ConcurrentLinkedQueue` or locked `ArrayList` would be cheaper to write to but
would require a lock or a volatile read on every `post`. `CopyOnWriteArrayList` shifts
all synchronisation cost to writes, leaving the read path allocation-free.

== Why dispatchCache <design-cache>

The first `post` of a given event type requires a BFS walk of the supertype hierarchy
to collect all applicable handler lists, followed by a merge and sort. For a class
with a non-trivial inheritance chain (implementing three interfaces, each with handlers
at different priorities) this is non-trivial work.

`dispatchCache` stores the result keyed by the concrete runtime class. Every subsequent
post of the same type is a single `ConcurrentHashMap.get` @concurrenthashmap plus a COWAL iteration.

The cache is fully invalidated on any `subscribe` or `unsubscribe` call. This is
intentionally conservative: partial invalidation (only evicting entries affected by the
changed handler) would save BFS on some re-posts but would add per-entry bookkeeping
that complicates the subscribe path. In practice, subscription happens at startup;
the cache is populated once and never invalidated again during steady-state operation.

`DispatchList` pre-computes `hasAnyAsync` at build time. The hot path in `post` checks
a single boolean rather than scanning the entries to decide whether to build a
`CompletableFuture` chain. This is especially valuable for all-sync buses, where the
chain allocation is skipped entirely (see @spec-sync-prefix).

These properties are verified by the JMH @jmh benchmark suite
(`AsyncDispatchBenchmark`), which measures sync post, async post, and fallback-to-sync
across handler counts of 1 and 4 in three scenarios: `ALL_SYNC`, `ALL_ASYNC`, and `MIXED`.

== Priority tie-breaking: registration order <design-priority-tiebreak>

Handlers with equal priority fire in registration order. This is implemented by keeping
the handler list sorted in insertion order within priority groups. The sort used
(`sortedByDescending { it.priority }`) is stable, so insertion order within a priority
band is preserved.

An alternative would be alphabetical by method name or random. Alphabetical would be
deterministic but would depend on naming conventions. Random would make tests fragile.
Registration order is predictable (objects are subscribed in code order) and matches
most developers' mental model.

== Typed bus as a zero-overhead adapter <design-typed>

`TypedEventBus<E>` is a thin interface backed by `TypedEventBusAdapter`, which holds
a reference to the underlying `EventBus` and delegates every call to it. There is no
additional data structure, no separate handler list, no secondary dispatch.

The compile-time constraint is enforced by the generic bound `T : E` on `post` and
`subscribe`. At runtime the adapter simply calls the underlying bus. The JIT inlines
the delegation call and the adapter disappears from the instruction stream.

Handlers registered via the typed view and handlers registered directly on the
underlying bus share the same dispatch list. A `TypedEventBus<NetworkEvent>` and the
underlying `EventBus` will dispatch to the same handlers in the same order.
The full typed bus contract is specified in @spec-typed.

== Async as a threading strategy layer, not a new dispatch model <design-async-model>

The design goal was to add async without forking the dispatch logic. The same
priority ordering, the same cancellation check, the same mutation visibility contract
all had to work.

The implementation achieves this by building one `CompletableFuture` chain that
represents the entire dispatch in order. Async steps are `thenApplyAsync`; sync steps
are `thenApply`. The chain is built eagerly before any step executes, so the order is
determined synchronously on the posting thread. Execution order is fully determined by
the chain structure; no step can overtake another.

Parallel execution of two handlers was never considered. It would violate D3 and D4
(mutation visibility and cancellation finality, see @spec-dispatch) and would require either immutable events
or explicit synchronisation on every field access. The model is strictly sequential,
asynchronous only in the sense of which thread each step runs on.

== Cancellation guard internals <design-cancellation-guard>

`AsyncCancellableGuard` wraps a `Cancellable` event during async dispatch. It holds an
`AtomicBoolean` for the duration of the chain. Reads and writes to `isCancelled` from
within the chain go through the atomic flag. Once the chain completes (the `postAsync`
future resolves), `flush()` writes the final flag value back to the original event field.

This design means the event's own field does not need to be volatile or atomic. The
event class is unaware of the wrapping. The guard is created by `SimpleEventBus` and is
not part of the public API.

The only visible effect on the user is the contract defined in @spec-cancellable: a plain
`var isCancelled = false` is sufficient for thread-safe cancellation in async dispatch.

== Why wildcard handlers have no async flag <design-wildcard>

Wildcards are designed for cross-cutting observers: logging, auditing, metrics. These
purposes are:

- Observational: they read the event, they do not mutate it.
- Cheap: a logger emitting a line is not worth executor overhead.
- Ubiquitous: a wildcard fires for every posted event, potentially thousands per second.

Allowing an async flag on `subscribeAll` would add API surface and complexity
(the `async` parameter, documentation for its interaction with the chain, tests for
every combination) for a use case that is already well-served by calling
`postAsync(event).thenAccept { sink.write(it) }` at the call site.

The constraint is explicit in the API (`subscribeAll` has no `async` parameter) and
documented. It is not an oversight. The wildcard specification is in @spec-wildcard;
usage examples are in @async-wildcard.

== Error routing asymmetry between `post` and `postAsync` <design-error-asymmetry>

`post` routes executor rejection to `exceptionHandler` and never throws. This preserves
a historical contract: callers of `post` are not in a position to handle a
`CompletionException`; they expect the method to return normally.

`postAsync` propagates executor rejection as an exceptional future completion. Callers
who hold a `CompletableFuture` are already equipped to handle exceptional states via
`exceptionally`, `whenComplete`, or `handle`. Silently routing infrastructure failure
to `exceptionHandler` when the caller explicitly asked for a future would hide real
problems.

The asymmetry is intentional and documented on both methods. The `post` contract is
in @spec-post; the `postAsync` contract is in @spec-postasync.

== Java interop design <design-java-interop>

Every Kotlin lambda overload is annotated `@JvmSynthetic`, which makes it invisible to
Java bytecode. Paired Java-facing overloads use `java.util.function.Consumer<T>` and
carry `@JvmStatic` / `@JvmName("create")` as needed so Java call sites look natural:

```java
EventBus bus = EventBus.create();
bus.subscribe(MyEvent.class, Priority.NORMAL, e -> handle(e));
```

This approach produces two overload sets: one that Kotlin callers see (lambdas, default
parameters, reified generics) and one that Java callers see (`Consumer`, static factory
methods, class tokens). The two sets share the same implementation; the Kotlin overloads
delegate to the Java-facing overloads or vice versa with a `@Suppress("UNCHECKED_CAST")`
on the unchecked coercion from `Event` to `T`.

The `TypedEventBus<E>` factory is in a file-level `@JvmName("TypedEventBusFactory")`
file so Java callers use `TypedEventBusFactory.typed(bus, NetworkEvent.class)`.
Kotlin callers use the `bus.typed<NetworkEvent>()` extension.
