#import "../theme.typ": *
#import "@preview/fletcher:0.5.8" as fletcher: diagram, node, edge

= Async Dispatch <async>

Async dispatch is an opt-in threading strategy layered over the synchronous dispatch
model. Every semantic guarantee -- priority order (D1), mutation visibility (D3),
cancellation finality (D4) -- is preserved. The only change is *where* each handler
executes.

Enabling async and basic usage are covered in the KDocs and module README. This chapter
covers the dispatch chain structure, the sync prefix optimisation, and the interactions
that are non-obvious from the API surface alone.

== The dispatch chain <async-chain>

When the dispatch list for an event contains at least one async handler, the bus
constructs a `CompletableFuture` @completablefuture chain across all handlers in
priority order. The pseudocode is:

```text
chain = completedFuture(Unit)

for each handler in priority order:
  if handler.async and executor is present:
    chain = chain.thenRunAsync(handler, executor)
  else if chain already has async steps:
    chain = chain.thenRun(handler)        // sequenced, not submitted
  else:
    handler.invoke() directly             // sync prefix; no chain involved
```

Key properties:

- *The chain is built eagerly on the posting thread* before any step executes.
  Priority order is determined synchronously; no step can overtake another.
- *`thenRunAsync`* submits the step to the executor. The executor decides when it runs.
- *`thenRun`* (no executor suffix) only runs after the prior step's future completes.
  This is how mutation visibility (D3) is enforced for sync handlers that follow async
  ones: the sync handler literally cannot start until the async one finishes.
- *The sync prefix* runs directly on the calling thread. No `CompletableFuture` is
  allocated for those steps. See @spec-sync-prefix.

=== Chain structure: visual example <async-chain-diagram>

Four handlers at HIGH (async), NORMAL (sync), LOW (async), LOWEST (sync):

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
        label: text(size: 6.5pt)[thenRunAsync], label-sep: 3pt),
      edge(<high>,   <normal>, "->",
        label: text(size: 6.5pt)[thenRun], label-sep: 3pt),
      edge(<normal>, <low>,    "-->",
        label: text(size: 6.5pt)[thenRunAsync], label-sep: 3pt),
      edge(<low>,    <lowest>, "->",
        label: text(size: 6.5pt)[thenRun], label-sep: 3pt),
    )
  },
  caption: [
    Dispatch chain in priority order. Dashed arrows (thenRunAsync) submit to the
    executor. Solid arrows (thenRun) continue on whichever thread completed the prior
    step. Blue = async; green = sync.
  ],
)

`NORMAL` sees mutations from `HIGH` because it cannot start until `HIGH` completes.
`LOWEST` sees mutations from all three prior handlers for the same reason.

Note that in this example there is no sync prefix: the first handler is async, so the
chain starts immediately from `post`. If HIGH were sync and NORMAL were the first async
handler, HIGH would execute directly on the posting thread before the chain is
constructed.

== Cancellation across threads <async-cancellation>

Cancellation is automatically thread-safe for async dispatch. The bus wraps the event's
`isCancelled` field in an `AsyncCancellableGuard` (an `AtomicBoolean`) for the duration
of the chain and writes the final result back once all handlers complete.

From the user's perspective: a plain `var isCancelled = false` is sufficient. See
@spec-cancellable for the formal contract and @design-cancellation-guard for the
mechanism.

The critical behaviour is at step boundaries. At the start of each chain step, the
guard reads `isCancelled` from the event under the happens-before edge provided by the
prior future's completion, then syncs that value into the atomic. This means a handler
that writes `isCancelled = true` directly to the event field (not to the guard) is
still correctly observed by the next step.

== Wildcard handlers in the chain <async-wildcard>

Wildcard handlers are always sync. When a wildcard falls after an async handler in
priority order it is inserted as a `thenRun` step and runs on whichever thread
completed the prior async step.

This has a non-obvious consequence for code that depends on thread-local state. See
@edge-wildcard-thread.

The rationale for not providing an `async` flag on wildcards is in @design-wildcard.

== Error routing <async-errors>

Handler exceptions are caught and passed to `exceptionHandler`. The chain continues to
the next step. This matches sync dispatch (D5).

`exceptionHandler` itself runs on whichever thread the failing handler was executing
on: the posting thread for a sync handler, or the configured executor's thread for an
async handler. Code inside `exceptionHandler` that assumes it always runs on the
posting thread -- for example, relying on thread-local state such as MDC -- will
observe the wrong context when the failing handler was async. This is the same class
of hazard as @edge-wildcard-thread, applied to the error path instead of the dispatch
path.

Executor rejection is handled differently between `post` and `postAsync`. This
asymmetry is non-obvious and documented as a dangerous edge in @edge-error-asymmetry.
The design rationale is in @design-error-asymmetry.
