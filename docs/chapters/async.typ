#import "../theme.typ": *
#import "@preview/fletcher:0.5.8" as fletcher: diagram, node, edge

= Async Dispatch <async>

Async dispatch is an opt-in threading strategy layered over the synchronous dispatch
model. Every semantic guarantee of sync dispatch (priority order, mutation visibility,
cancellation) is preserved -- see @spec-dispatch. The only change is *where* a handler
executes.

== Enabling async dispatch <async-enabling>

Async dispatch requires two things: an executor on the bus and an opt-in flag on
the handler.

*Executor at construction:*

```kotlin
val bus = EventBus(Executors.newVirtualThreadPerTaskExecutor())
```

```java
EventBus bus = EventBus.create(Executors.newVirtualThreadPerTaskExecutor());
```

Virtual threads @jep444 are the recommended executor. They are cheap to create, block without
consuming platform threads, and eliminate the deadlock risk that bounded thread pools
carry (see below).

*Opt-in per handler:*

```kotlin
// Annotation style
@Subscribe(priority = Priority.HIGH, async = true)
fun onPacket(e: PacketReceived) { /* runs on virtual thread */ }

// Lambda style
val sub = bus.subscribe<PacketReceived>(async = true) { e -> handle(e) }
```

```java
// Java Consumer style
bus.subscribe(PacketReceived.class, Priority.NORMAL, true, e -> handle(e));
```

If no executor is configured, `async = true` handlers fall back to synchronous
execution silently. No exception is thrown; the flag is treated as a hint.

== The dispatch chain <async-chain>

When the dispatch list for an event contains at least one async handler, the bus
constructs a `CompletableFuture` @completablefuture chain across all handlers in priority order
(see @spec-sync-prefix for the sync prefix optimisation):

```text
chain = completedFuture(Unit)

for each handler in priority order:
  if handler.async:
    chain = chain.thenApplyAsync(_, executor)    // submit to executor
  else if chain has async steps:
    chain = chain.thenApply(_)                   // run after prior async completes
  else:
    handler.invoke() directly                    // sync prefix: no chain involved
```

Properties of this chain:

- The chain is built eagerly on the posting thread before any step executes.
- `thenApplyAsync` submits to the executor and returns immediately; the executor
  decides when the step actually runs.
- `thenApply` (no executor) after a `thenApplyAsync` step only runs after that
  prior step completes, which is what preserves mutation visibility for sync
  handlers that follow async ones.
- The leading sync prefix (handlers before the first async one) runs directly on
  the calling thread; no `CompletableFuture` is allocated for them.

=== Visual example

Given four handlers registered at priorities HIGH (async), NORMAL (sync), LOW (async),
LOWEST (sync):

#figure(
  {
    set text(size: 9pt)
    diagram(
      node-stroke: 0.8pt,
      node-corner-radius: 4pt,
      node-inset: 10pt,
      spacing: (18mm, 0mm),

      node((0,0), [*post*],
        fill: luma(240), shape: rect, name: <post>),

      node((1,0), [*HIGH*\ #text(size: 7.5pt, fill: luma(100))[async]],
        fill: rgb("#cce0ff"), shape: rect, name: <high>),

      node((2,0), [*NORMAL*\ #text(size: 7.5pt, fill: luma(100))[sync]],
        fill: rgb("#cceecc"), shape: rect, name: <normal>),

      node((3,0), [*LOW*\ #text(size: 7.5pt, fill: luma(100))[async]],
        fill: rgb("#cce0ff"), shape: rect, name: <low>),

      node((4,0), [*LOWEST*\ #text(size: 7.5pt, fill: luma(100))[sync]],
        fill: rgb("#cceecc"), shape: rect, name: <lowest>),

      edge(<post>,   <high>,   "-->",
        label: text(size: 6.5pt)[thenApplyAsync], label-sep: 3pt),
      edge(<high>,   <normal>, "->",
        label: text(size: 6.5pt)[thenApply], label-sep: 3pt),
      edge(<normal>, <low>,    "-->",
        label: text(size: 6.5pt)[thenApplyAsync], label-sep: 3pt),
      edge(<low>,    <lowest>, "->",
        label: text(size: 6.5pt)[thenApply], label-sep: 3pt),
    )
  },
  caption: [
    Dispatch chain in priority order. Dashed arrows (`thenApplyAsync`) submit a handler
    to the executor on a new thread. Solid arrows (`thenApply`) continue on whichever
    thread completed the prior step. Blue = async handler; green = sync handler.
  ],
)

`NORMAL` sees mutations from `HIGH`. `LOWEST` sees mutations from all three
prior handlers. The priority contract is fully honoured.

== Posting modes <async-posting>

=== `postAsync` <async-postasync>

Returns a `CompletableFuture<T>` immediately. The future completes with the event
after all handlers finish.

```kotlin
val future = bus.postAsync(PacketReceived(bytes))
// Attach a callback - runs when all handlers are done
future.thenAccept { e -> println("dispatch complete") }
// Or block if you need the result synchronously from here
val event = future.join()
```

Handler exceptions are never propagated through the future. They route to
`exceptionHandler` and the future still completes normally (see @spec-dispatch). Only
infrastructure failure (executor rejection) causes the future to complete exceptionally.

=== `post` <async-post>

Blocks the calling thread until all handlers complete, then returns the event.
Semantically equivalent to `postAsync(event).join()`, with one difference: executor
rejection is routed to `exceptionHandler` rather than propagating as a
`CompletionException`. `post` never throws. See @spec-post for the full contract.

```kotlin
val event = bus.post(PacketReceived(bytes))
// All handlers have finished; event mutations are visible here
```

#warn(title: "Deadlock risk on bounded thread pools.")[
  When `post` is called from a thread that belongs to the same bounded pool used as
  the bus's executor, the calling thread parks waiting for the chain. The chain needs
  a free pool thread to proceed. If the pool is saturated, no thread is available and
  the system deadlocks. Virtual threads avoid this entirely because they do not hold
  a platform thread while parked.

  Calling `postAsync` instead leaves the decision of when to wait to the caller,
  which breaks the deadlock.
]

== Cancellation across threads <async-cancellation>

Cancellation is automatically thread-safe for async dispatch. The implementation
wraps the event's `isCancelled` field in an `AtomicBoolean` for the duration of
the chain and writes the final result back once all handlers complete.

From the user's perspective: implement `Cancellable` with a plain `var isCancelled = false`.
No annotations, no atomic fields, no synchronisation primitives required.
The thread-safety contract is formally specified in @spec-cancellable.

```kotlin
class RequestEvent(val url: String) : Event, Cancellable {
    override var isCancelled = false
}

bus.subscribe<RequestEvent>(Priority.HIGH, async = true) { e ->
    if (!isValid(e.url)) e.cancel()  // safe across threads
}
bus.subscribe<RequestEvent>(Priority.LOW) { e ->
    // Will not run if HIGH cancelled the event, even though HIGH ran on another thread
}
```

The cancellation check between steps in the chain is performed at chain-build time
(the chain is built eagerly). For the async path, effective cancellation detection
occurs at each `thenApply` step before invoking the handler. A handler that sets
`isCancelled = true` prevents all subsequent steps in the chain from invoking their
handler.

== Wildcard handlers and async <async-wildcard>

Wildcard handlers registered via `subscribeAll` are always synchronous. There is no
`async` flag for wildcards. This is a deliberate constraint -- see @design-wildcard
for the full rationale.

- Wildcards are intended for cross-cutting concerns: logging, auditing, metrics.
  These are observers; they do not mutate events, and submitting each one to
  an executor would add scheduling overhead for no benefit.
- Making wildcards async would complicate the chain structure and add API surface
  (an `async` flag on `subscribeAll`) for a use case that the API is not designed around.

When a wildcard falls after an async handler in the priority order, it is inserted
into the chain as a `thenApply` step. It runs on whichever virtual thread completed
the prior async step, not the posting thread.

#note[
  If you need an async observer (e.g. writing every event to a database without blocking
  the chain), the recommended pattern is `postAsync(event).thenAccept { auditSink.write(it) }`
  at the call site. This keeps the bus chain synchronous and moves the async work outside
  the dispatch boundary.
]

== Error handling in async dispatch <async-errors>

Handler exceptions behave identically to sync dispatch: they are caught and passed
to the bus's `exceptionHandler`. The chain continues to the next step.

Executor rejection (`RejectedExecutionException`) is handled differently for the
two posting modes:

- *`post`*: the rejection is caught and passed to `exceptionHandler`. `post` returns
  normally. This preserves `post`'s historical no-throw contract.
- *`postAsync`*: the rejection propagates as a `CompletionException` completing the
  future exceptionally. The caller can inspect it via `future.exceptionally { ... }`.

The asymmetry is intentional. `post` callers have no mechanism to observe a failed
future; routing to `exceptionHandler` is the only option. `postAsync` callers hold
the future and can choose to handle infrastructure failure differently from handler
errors. The design rationale is in @design-error-asymmetry.
