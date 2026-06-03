#import "../theme.typ": *

= Behavioural Specification <spec>

This chapter defines the contracts and invariants that all conforming implementations of
`EventBus` must satisfy. The reference implementation is `SimpleEventBus`.

== Dispatch invariants <spec-dispatch>

The following invariants hold for every call to `post` and `postAsync`, regardless of
the event type, handler count, or async configuration.

#contract(title: "Invariant D1 - Priority ordering.")[
  Handlers execute in strictly descending order of their declared priority value.
  Among handlers with equal priority, execution order matches registration order.
  This ordering is never violated by the presence of async handlers.
] <d1>

#contract(title: "Invariant D2 - Supertype reach.")[
  A call to `post(e)` dispatches to every handler whose declared parameter type is
  assignable from the runtime type of `e`. A handler for `Animal` fires when a `Dog`
  is posted, provided `Dog extends Animal`. Wildcard handlers (`subscribeAll`) always
  fire regardless of event type.
] <d2>

#contract(title: "Invariant D3 - Mutation visibility.")[
  Each handler observes all mutations made to the event by higher-priority handlers
  that have already completed. For purely synchronous dispatch this follows from
  sequential execution. For async dispatch it is enforced structurally: a sync handler
  that follows an async handler in priority order is placed in the `CompletableFuture`
  chain as a `thenApply` step, which only runs after the prior async step completes.
  Parallel execution of two handlers on the same event is never performed.
] <d3>

#contract(title: "Invariant D4 - Cancellation finality.")[
  Once any handler sets `isCancelled = true` on a `Cancellable` event, no subsequent
  handler in the dispatch list executes. This check spans thread boundaries: a handler
  that runs on an executor thread and cancels the event will prevent all lower-priority
  handlers from running, whether those handlers are async or sync.
] <d4>

#contract(title: "Invariant D5 - Exception isolation.")[
  An exception thrown inside a handler is routed to the bus's `exceptionHandler`.
  It does not propagate out of `post`, does not prevent remaining handlers from
  running, and does not affect the return value of `post`.
] <d5>

#contract(title: "Invariant D6 - Return identity.")[
  `post(e)` returns the same object reference that was passed in. The return type
  is `T` (not `T?`), and the returned value is always the original event instance.
] <d6>

== Registration invariants <spec-registration>

#contract(title: "Invariant R1 - Idempotent annotation registration.")[
  Calling `subscribe(obj)` more than once with the same object is a no-op after the
  first call. The handlers registered on the first call are not duplicated. Identity
  is tracked by `System.identityHashCode` combined with the method reference, so two
  distinct instances of the same class each register their own set of handlers.
] <r1>

#contract(title: "Invariant R2 - Idempotent subscription cancellation.")[
  Calling `cancel()` on a `Subscription` token more than once is safe. Subsequent
  calls after the first are no-ops. The handler is guaranteed to be absent from the
  bus after the first `cancel()` returns.
] <r2>

#contract(title: "Invariant R3 - Annotation handler requirements.")[
  A method annotated with `@Subscribe` must satisfy all of the following for the bus
  to register it. If any condition is not met the method is silently skipped:
  - Exactly one parameter, whose type implements `Event`.
  - Return type is `Unit` / `void`.
  - Is an instance method (for `subscribe(obj)`) or a static / `@JvmStatic` method
    (for `subscribeStatic(Class)`).
] <r3>

#contract(title: "Invariant R4 - Override annotation requirement.")[
  Annotation scanning is performed on the declared methods of the provided class.
  If a subclass overrides a `@Subscribe` method without re-annotating the override,
  the overridden method is not registered for that subclass instance. Both methods
  must carry `@Subscribe` independently.
] <r4>

== `post` contract <spec-post>

```kotlin
fun <T : Event> post(event: T): T
```

- Dispatches `event` synchronously. If no async handlers are present, the entire
  dispatch completes on the calling thread before returning.
- If async handlers are present and an executor is configured, the calling thread
  blocks until all handlers (sync and async) complete. This is equivalent to
  `postAsync(event).join()` but with one additional guarantee: infrastructure
  failures (executor rejection) are routed to `exceptionHandler` rather than
  propagating as a `CompletionException`. `post` never throws.
- Returns `event` (same reference, non-null). See D6 in @spec-dispatch.

#warn(title: "Blocking behaviour with async handlers.")[
  When any handler is marked `async = true`
  and the bus has an executor, `post` parks the calling thread until the dispatch chain
  completes. This is safe with virtual-thread executors. On bounded platform-thread pools
  it is a potential deadlock if a handler on the pool's threads re-entrantly calls `post`
  on the same bus with async handlers -- the calling thread holds a pool slot while
  waiting for the chain, which needs another pool slot to progress. Prefer `postAsync`
  (see @spec-postasync) in that scenario, or use virtual threads.
]

== `postAsync` contract <spec-postasync>

```kotlin
fun <T : Event> postAsync(event: T): CompletableFuture<T>
```

- Begins dispatch of `event` and returns a `CompletableFuture<T>` immediately.
- The future completes with `event` after all handlers have finished.
- The future never completes exceptionally due to handler errors; those are always
  routed to `exceptionHandler` (see D5 in @spec-dispatch).
- The future completes exceptionally only on infrastructure failure, specifically
  executor rejection (`RejectedExecutionException` wrapped in `CompletionException`).
  This is intentionally asymmetric with `post` (see @spec-post): callers who need to distinguish
  infrastructure failure from handler errors use `postAsync`.
- If no executor is configured, all handlers run synchronously on the calling thread
  and the returned future is already completed on return.
- All invariants D1 through D6 (see @spec-dispatch) apply.

== Priority semantics <spec-priority>

Priority is a plain `Int`. Any integer is valid. The five named constants in `Priority`
are conventional reference points, not the only legal values. A priority of `150` fires
after `HIGHEST` (200) but before `HIGH` (100).

#table(
  columns: (auto, auto, 1fr),
  stroke: 0.5pt + luma(200),
  inset: (x: 0.8em, y: 0.55em),
  fill: (_, row) => if calc.odd(row) { luma(248) } else { white },
  table.header(
    [*Constant*], [*Value*], [*Conventional use*],
  ),
  [`HIGHEST`], [`200`],  [Security checks, authentication guards.],
  [`HIGH`],    [`100`],  [Core game logic, primary transformations.],
  [`NORMAL`],  [`0`],    [Default. General-purpose handlers.],
  [`LOW`],     [`-100`], [Secondary effects, derived values.],
  [`LOWEST`],  [`-200`], [Logging, metrics, post-processing observers.],
)

Handlers with equal priority fire in registration order (first registered, first called).
This is an implementation guarantee of `SimpleEventBus`, not a general interface contract;
custom bus implementations may define their own tie-breaking rule.

== Wildcard handlers <spec-wildcard>

A wildcard handler registered via `subscribeAll(priority, handler)` fires for every
posted event regardless of type. It participates in the same priority-sorted dispatch
list as typed handlers and obeys all cancellation invariants (D4, see @spec-dispatch).

Wildcard handlers are always synchronous. They carry no `async` flag.
When a wildcard falls after an async handler in the chain, it runs on whichever thread
completed the prior async step -- not the posting thread.

#warn(title: "Thread-local state in wildcard handlers.")[
  Code that depends on thread-local state (MDC logging context, security principals)
  must not be used in a wildcard handler on a bus that has async handlers configured.
  The posting thread's context is not propagated to executor threads.
  See @async-wildcard for the full async interaction.
]

== TypedEventBus contract <spec-typed>

`TypedEventBus<E>` is a compile-time view over an underlying `EventBus`. It adds no
runtime behaviour. The following holds:

- `post(e)` on the typed view delegates to `post(e)` on the underlying bus (see @spec-post).
- All invariants D1 through D6 (see @spec-dispatch) apply to the underlying bus and are therefore inherited
  by the typed view.
- Handlers registered on the typed view and handlers registered on the underlying bus
  are in the same dispatch list and fire together.
- `delegate` exposes the underlying bus for operations outside the typed interface
  (e.g. `subscribeStatic`, `unsubscribeAll`; see @spec-static).

== Modifiable: value visibility contract <spec-modifiable>

`Modifiable<T>` events carry a `var value: T` field that any handler may read or
replace. The following invariants hold:

#contract(title: "Invariant M1 - Sequential visibility.")[
  Each handler in the dispatch chain observes the `value` written by all
  higher-priority handlers that have already completed, regardless of whether
  those handlers ran synchronously or on an executor thread. This is a direct
  consequence of D3 (see @spec-dispatch): no two handlers run concurrently,
  so every write by handler N is visible to handler N+1 when it begins.
] <m1>

#contract(title: "Invariant M2 - No atomic field required.")[
  A plain `var value: T` field is sufficient for correct mutation visibility
  across async handlers. The sequential chain structure (D3, @spec-dispatch) eliminates the
  need for `@Volatile`, `AtomicReference`, or any other synchronisation on
  the value field. This mirrors the guarantee described in @spec-cancellable.
] <m2>

#contract(title: "Invariant M3 - Post-dispatch visibility.")[
  After `post(e)` returns or `postAsync(e).join()` completes, all mutations
  made to `e.value` by any handler are visible to the caller on the posting
  thread. The `CompletableFuture` chain enforces a happens-before edge between
  the last handler completing and the future resolving.
] <m3>

== `subscribeStatic` and `unsubscribeAll` <spec-static>

#contract(title: "Invariant S1 - Static handler registration.")[
  `subscribeStatic(Class<*>)` scans for methods annotated with `@Subscribe` that
  are `static` (Java) or `@JvmStatic` (Kotlin companion/object). The rules in
  R3 and R4 (see @spec-registration) apply identically. The class itself, not an instance, is the
  registration key. Calling `subscribeStatic` with the same class more than
  once is idempotent.
] <s1>

#contract(title: "Invariant S2 - unsubscribeAll clears all state.")[
  `unsubscribeAll()` removes every registered handler from the bus -- lambda
  subscriptions, annotation-based instance handlers, and static handlers -- and
  fully invalidates the dispatch cache. After `unsubscribeAll()` returns, a
  call to `post(e)` for any event type will invoke zero handlers.

  #warn(title: "Concurrent use.")[
    Calling `unsubscribeAll()` while a `post` is in progress on another thread
    is safe at the data-structure level (the underlying lists are
    `CopyOnWriteArrayList`), but the in-flight dispatch operates on a snapshot
    taken before the clear. Handlers that were registered at the time `post`
    was called may still execute after `unsubscribeAll()` returns.
  ]
] <s2>

== Cancellable: thread-safety contract <spec-cancellable>

When a `Cancellable` event is dispatched through a bus with async handlers, the bus
wraps cancellation in an `AtomicBoolean` guard internally for the duration of the
dispatch chain. The result is written back to the event's `isCancelled` field once all
handlers complete.

As a consequence: a plain `var isCancelled = false` field is sufficient. The event
class does not need to use `AtomicBoolean`, `@Volatile`, or any synchronisation
mechanism. This wrapping is transparent to both producer and consumer code.
See @async-cancellation for usage examples.

== Handler execution model: sync prefix optimisation <spec-sync-prefix>

When the dispatch list begins with one or more sync handlers before the first async
handler, those leading sync handlers execute directly on the calling thread with no
`CompletableFuture` involved. The chain is only constructed starting from the first
async handler. This means:

- A bus with only sync handlers incurs zero `CompletableFuture` allocation on `post`.
- A bus with a sync handler at `HIGHEST` followed by an async handler at `NORMAL`
  runs the `HIGHEST` handler directly, then builds a chain for the `NORMAL` handler.
- `postAsync` on a bus with no executor and only `async = true` handlers behaves
  identically to sync `post`, returning an already-completed future.
