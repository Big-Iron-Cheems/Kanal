#import "../theme.typ": *

= Behavioural Specification <spec>

This chapter defines the contracts that all conforming `EventBus` implementations
must satisfy. The reference implementation is `SimpleEventBus`. Every invariant stated
here is verified by the test suite; a violation is a bug.

For API signatures and usage, see the KDocs. For non-obvious runtime behaviour that
follows from these invariants, see @edges.

== Dispatch invariants <spec-dispatch>

These invariants hold for every call to `post` and `postAsync`, regardless of event
type, handler count, or async configuration.

#contract(id: "D1", title: "Priority ordering.")[
  Handlers execute in strictly descending order of their declared priority value.
  Among handlers with equal priority, execution order matches registration order.
  This ordering is never violated by the presence of async handlers or wildcard
  handlers: all handlers, regardless of origin, are merged into a single
  priority-sorted list before dispatch begins.
] <d1>

#contract(id: "D2", title: "Supertype reach.")[
  `post(e)` dispatches to every handler whose declared parameter type is assignable
  from the runtime type of `e`. A handler for `Animal` fires when a `Dog` is posted,
  provided `Dog : Animal`. Wildcard handlers fire for every event regardless of type.
  The merged dispatch list is cached after the first post of each concrete type
  (see @design-cache).
] <d2>

#contract(id: "D3", title: "Mutation visibility.")[
  Each handler observes all mutations made to the event by higher-priority handlers
  that have already completed. For sync dispatch this follows from sequential execution.
  For async dispatch it is enforced structurally: every step in the `CompletableFuture`
  chain is sequenced after the prior step completes, so no two handlers on the same
  event ever execute concurrently. Parallel execution of handlers is never performed.
] <d3>

#contract(id: "D4", title: "Cancellation finality.")[
  Once any handler sets `isCancelled = true` on a `Cancellable` event, no subsequent
  handler in the dispatch list executes. This check spans thread boundaries: an async
  handler that cancels the event on an executor thread prevents all lower-priority
  handlers from running, whether those handlers are async or sync.
] <d4>

#contract(id: "D5", title: "Exception isolation.")[
  An exception thrown inside a handler is routed to the bus's `exceptionHandler`.
  It does not propagate out of `post`, does not prevent remaining handlers from
  running, and does not affect the return value of `post`.

  If `exceptionHandler` itself throws while handling a handler's exception, the
  secondary exception is caught internally and printed to stderr. It is *not*
  routed back through `exceptionHandler` recursively, does not propagate out of
  `post` or `postAsync`, and does not prevent remaining handlers from running --
  the same guarantee applies transitively. See @edge-exceptionhandler-failure
  for the practical implication.
] <d5>

#contract(id: "D6", title: "Return identity.")[
  `post(e)` returns the same object reference that was passed in. The returned value
  is always the original event instance, non-null, after all handlers have completed.
] <d6>

== Registration invariants <spec-registration>

#contract(id: "R1", title: "Idempotent annotation registration.")[
  Calling `subscribe(obj)` more than once with the same object is a no-op after the
  first call. Identity is tracked by true object reference (via `IdentityHashMap`),
  not a hash value, so two distinct instances of the same class always each register
  their own handlers independently, with no possibility of cross-object interference.
  See @design-registration-identity for the rationale.
] <r1>

#contract(id: "R2", title: "Idempotent subscription cancellation.")[
  Calling `cancel()` on a `Subscription` token more than once is safe. Subsequent calls
  are no-ops. The handler is absent from the bus after the first `cancel()` returns.
] <r2>

#contract(id: "R3", title: "Annotation handler requirements.")[
  A method annotated with `@Subscribe` is registered only if all of the following hold.
  If any condition is not met the method is silently skipped:
  - Exactly one parameter whose type implements `Event`.
  - Return type is `Unit` / `void`.
  - Is an instance method (for `subscribe(obj)`) or a `static` / `@JvmStatic` method
    (for `subscribeStatic(Class)`).
] <r3>

#contract(id: "R4", title: "Override annotation requirement.")[
  If a subclass overrides a `@Subscribe` method without re-annotating the override,
  the overriding method is not registered. The `@Subscribe` annotation is not inherited.
  Re-annotate every override that should participate in dispatch.
  See @edge-override for the full implication.
] <r4>

== `post` contract <spec-post>

- Dispatches synchronously. If no async handlers are present the entire dispatch
  completes on the calling thread before returning.
- If async handlers are present and an executor is configured, the calling thread blocks
  until all handlers complete. This is equivalent to `postAsync(event).join()` but with
  one additional guarantee: executor rejection is routed to `exceptionHandler` rather
  than propagating as a `CompletionException`. `post` never throws.
- Returns `event` (same reference). See @d6.

== `postAsync` contract <spec-postasync>

- Returns a `CompletableFuture<T>` immediately; completes with `event` after all
  handlers finish.
- Never completes exceptionally due to handler errors (see @d5). Completes
  exceptionally only on executor rejection.
- If no executor is configured, all handlers run synchronously on the calling thread
  and the returned future is already completed on return.
- All invariants @d1 through @d6 apply.

The error routing asymmetry between `post` and `postAsync` is intentional; see
@design-error-asymmetry for the rationale and @edge-error-asymmetry for the
practical implication.

== Priority semantics <spec-priority>

Priority is a plain `Int`. Any integer is valid; the five named constants in `Priority`
are conventional reference points, not the only legal values.

#table(
  columns: (auto, auto, 1fr),
  stroke: 0.5pt + luma(200),
  inset: (x: 0.8em, y: 0.55em),
  fill: (_, row) => if calc.odd(row) { luma(248) } else { white },
  table.header(
    [*Constant*], [*Value*], [*Conventional use*],
  ),
  [`HIGHEST`], [`200`],  [Security checks, authentication guards.],
  [`HIGH`],    [`100`],  [Core logic, primary transformations.],
  [`NORMAL`],  [`0`],    [Default. General-purpose handlers.],
  [`LOW`],     [`-100`], [Secondary effects, derived values.],
  [`LOWEST`],  [`-200`], [Logging, metrics, post-processing observers.],
)

Handlers with equal priority fire in registration order. This is a guarantee of
`SimpleEventBus`, not a general interface contract.

== Wildcard handlers <spec-wildcard>

`subscribeAll(priority, handler)` registers a handler that fires for every posted event.
Wildcard handlers participate in the same priority-sorted dispatch list as typed handlers
and obey all cancellation invariants (@d4).

Wildcard handlers are always synchronous. There is no `async` flag. When a wildcard
falls after an async handler in priority order, it runs on whichever thread completed
the prior async step, not the posting thread. See @edge-wildcard-thread for the full
implication.

== TypedEventBus contract <spec-typed>

`TypedEventBus<E>` is a compile-time view over an underlying `EventBus`. It adds no
runtime behaviour:

- `post(e)` on the typed view delegates to `post(e)` on the underlying bus.
- All invariants @d1 through @d6 apply to the underlying bus and are therefore inherited.
- Handlers registered on the typed view and on the underlying bus are in the same
  dispatch list and fire together.
- `delegate` exposes the underlying bus for operations outside the typed interface.

== Modifiable: value visibility contract <spec-modifiable>

`Modifiable<T>` events carry a `var value: T` field that any handler may read or
replace.

#contract(id: "M1", title: "Sequential visibility.")[
  Each handler observes the `value` written by all higher-priority handlers that have
  already completed, regardless of whether those handlers ran synchronously or on an
  executor thread. This is a direct consequence of @d3: no two handlers execute
  concurrently, so every write by handler N is visible to handler N+1.
] <m1>

#contract(id: "M2", title: "No atomic field required.")[
  A plain `var value: T` field is sufficient for correct mutation visibility across
  async handlers. The sequential chain structure (@d3) eliminates the need for
  `@Volatile`, `AtomicReference`, or any other synchronisation on the value field.
] <m2>

#contract(id: "M3", title: "Post-dispatch visibility.")[
  After `post(e)` returns or `postAsync(e).join()` completes, all mutations made to
  `e.value` by any handler are visible to the caller on the posting thread. The
  `CompletableFuture` chain enforces a happens-before edge between the last handler
  completing and the future resolving.
] <m3>

== Cancellable: thread-safety contract <spec-cancellable>

When a `Cancellable` event is dispatched through a bus with async handlers, the bus
wraps cancellation in an `AtomicBoolean` guard internally for the duration of the
dispatch chain. The result is written back to the event's `isCancelled` field once all
handlers complete. See @design-cancellation-guard for the mechanism.

*A plain `var isCancelled = false` field is sufficient.* No `AtomicBoolean`, no
`@Volatile`, no synchronisation on the event class. The wrapping is entirely internal.

== `subscribeStatic` and `unsubscribeAll` <spec-static>

#contract(id: "S1", title: "Static handler registration.")[
  `subscribeStatic(Class<*>)` scans for methods annotated with `@Subscribe` that are
  `static` (Java) or `@JvmStatic` (Kotlin). Rules @r3 and @r4 apply identically. The
  class itself is the registration key; calling `subscribeStatic` with the same class
  more than once is idempotent.
] <s1>

#contract(id: "S2", title: "unsubscribeAll clears all state.")[
  `unsubscribeAll()` removes every registered handler and fully invalidates the dispatch
  cache. After it returns, `post(e)` for any event type invokes zero handlers.

  #note[
    Calling `unsubscribeAll()` while a `post` is in progress on another thread is safe at
    the data-structure level (COWAL snapshot semantics), but the in-flight dispatch operates
    on the snapshot taken before the clear. Handlers registered at the time `post` was
    called may still execute after `unsubscribeAll()` returns.
  ]
] <s2>

== Handler execution model: sync prefix optimisation <spec-sync-prefix>

When the dispatch list begins with sync handlers before the first async handler, those
leading sync handlers execute directly on the calling thread with no `CompletableFuture`
involved. The chain is only constructed starting from the first async handler.

Consequences:
- A bus with only sync handlers incurs zero `CompletableFuture` allocation on every
  `post` call.
- A bus with a sync handler at `HIGHEST` followed by an async handler at `NORMAL` runs
  the `HIGHEST` handler directly on the posting thread, then builds the chain.
- `postAsync` on a no-executor bus returns an already-completed future with no chain
  allocation regardless of how many handlers are registered.

== Coroutines invariants <spec-coroutines>

These invariants apply to the `kanal-coroutines` module.

#contract(id: "C1", title: "suspendHandler registration is synchronous.")[
  `suspendHandler` registers its underlying `Subscription` before returning. The handler
  is active immediately; events can be posted on the next line without any timing concern.
] <c1>

#contract(id: "C2", title: "nextEvent single-resume guarantee.")[
  `nextEvent` resumes the calling coroutine at most once. If two matching events arrive
  on different threads simultaneously, exactly one resumes the continuation; the other
  is discarded. This is enforced by an `AtomicBoolean` claimed flag rather than an
  `isActive` check (see @design-nextevent for the race analysis).
] <c2>

#contract(id: "C3", title: "asFlow subscription lifetime.")[
  The subscription backing an `asFlow` collector is registered when the collector
  coroutine body begins executing, and removed when the collector is cancelled or
  completes. There is no subscription before collection starts and none after it ends.
] <c3>

#contract(id: "C4", title: "suspendHandler scope ownership.")[
  When `suspendHandler` is called with no explicit scope, it creates an internal scope.
  `sub.cancel()` cancels that scope, stopping all in-flight handler coroutines.

  When an explicit scope is provided, `sub.cancel()` removes the subscription from the
  bus but does *not* cancel the provided scope. The caller retains full lifecycle
  ownership.
] <c4>

#contract(id: "C5", title: "postSuspend context semantics.")[
  `postSuspend` wraps `EventBus.post` in `withContext(context)`. The default context is
  `EmptyCoroutineContext`, which causes dispatch to run on the caller's current
  dispatcher with no thread hop. Passing an explicit context shifts dispatch to that
  dispatcher.
] <c5>
