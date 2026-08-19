#import "../theme.typ": *

= Glossary <glossary>

Terms used throughout this manual. For API signatures and usage, see the KDocs.

#gterm([`async` flag])[
  A boolean on `@Subscribe` and lambda `subscribe` overloads. When `true` and an
  executor is configured on the bus, the handler runs on the executor rather than the
  posting thread. Without an executor the flag is treated as a hint and the handler
  runs synchronously. See @async-chain.
]

#gterm([`asFlow`])[
  A `kanal-coroutines` extension that exposes an event type as a `Flow<T>`. The
  subscription is registered when the collector starts and removed when it is cancelled.
  Uses `callbackFlow` internally. Subject to subscription timing constraints;
  see @coroutines-timing and @edge-timing.
]

#gterm([Cancellable])[
  An interface an event class may implement to support mid-chain cancellation. Once any
  handler sets `isCancelled = true`, all lower-priority handlers are skipped. A plain
  `var isCancelled = false` field is sufficient; no atomic or volatile declaration is
  needed. See @spec-cancellable.
]

#gterm([dispatch cache])[
  A `ConcurrentHashMap` keyed by the concrete runtime type of the posted event. Stores
  the pre-sorted, pre-merged handler list so that subsequent posts of the same type skip
  the supertype BFS walk. Invalidated on every subscribe or unsubscribe call.
  See @design-cache.
]

#gterm([dispatch chain])[
  A `CompletableFuture` pipeline built when at least one handler in the dispatch list
  carries `async = true`. Async steps use `thenRunAsync`; sync steps after an async
  step use `thenRun`. The chain is built eagerly before any step executes. See @async-chain.
]

#gterm([event])[
  Any class that implements the `Event` marker interface. No base class, no
  registration, and no annotation on the class itself is required.
]

#gterm([`EventBus`])[
  The primary API type. Manages handler registration, dispatch, and lifecycle. The
  default implementation is `SimpleEventBus`. See @spec-post.
]

#gterm([`exceptionHandler`])[
  A `(Throwable) -> Unit` callback configured on the bus. Handler exceptions are always
  routed here rather than propagating out of `post`. Runs on the posting thread for
  sync handlers, or the configured executor's thread for async handlers (see
  @async-errors). If this callback itself throws, the secondary exception is printed
  to stderr rather than propagating; see @edge-exceptionhandler-failure. See @d5.
]

#gterm([executor])[
  A `java.util.concurrent.Executor` configured at bus construction time. Used to run
  handlers marked `async = true`. Virtual-thread executors are recommended.
  See @jep444 and @edge-post-blocking.
]

#gterm([`LambdaMetafactory`])[
  A JDK API used to turn `@Subscribe`-annotated methods into `Consumer<Event>` instances
  at subscription time. The generated consumer calls the target method directly, making
  dispatch equivalent to a direct call after JIT inlining. See @design-lambdametafactory.
]

#gterm([`Modifiable<T>`])[
  An interface an event class may implement to expose a mutable `value: T` field. Each
  handler observes writes from all higher-priority handlers that already completed.
  A plain `var value` field is sufficient. See @spec-modifiable.
]

#gterm([`nextEvent`])[
  A `kanal-coroutines` suspend function that resumes with the next matching event.
  Single-shot: the subscription is unregistered after the first match or on cancellation.
  Uses an `AtomicBoolean` claimed flag to prevent concurrent double-resume.
  See @coroutines-nextevent-safety, @c2, and @design-nextevent.
]

#gterm([`nextEventOrNull`])[
  Like `nextEvent` but with a timeout `Duration`. Returns `null` if no matching event
  arrives before the timeout elapses.
]

#gterm([`postSuspend`])[
  A `kanal-coroutines` suspend function that dispatches an event from a coroutine
  context. Wraps `EventBus.post` in `withContext`. Default context is
  `EmptyCoroutineContext` (no thread hop). See @c5.
]

#gterm([priority])[
  A plain `Int` attached to every handler. Higher values fire first. Five named
  constants: `HIGHEST` (200) through `LOWEST` (-200). Any integer is valid.
  See @spec-priority.
]

#gterm([subscription])[
  A token returned by lambda-based registration. `cancel()` removes the handler.
  `cancel()` is idempotent. See @r2.
]

#gterm([subscription timing])[
  The window between launching a coroutine and the moment its subscription is actually
  registered. Relevant for `asFlow` and `nextEvent` in `runBlocking` + `launch`
  contexts. See @coroutines-timing and @edge-timing.
]

#gterm([`suspendHandler`])[
  A `kanal-coroutines` extension that registers a suspend lambda handler synchronously
  and returns a `Subscription` token. No subscription timing concerns. Accepts a
  `SuspendHandlerBehaviour` for concurrency control. See @c1, @c4,
  @coroutines-scope-ownership, and @design-suspendhandler-scope.
]

#gterm([`SuspendHandlerBehaviour`])[
  A sealed interface with three implementations: `Parallel` (new coroutine per event),
  `DiscardIfBusy` (skip if previous still running), `ReplaceLatest` (cancel previous,
  launch new).
]

#gterm([sync prefix optimisation])[
  Leading sync handlers in a dispatch list run directly on the calling thread with no
  `CompletableFuture` allocated. The chain is only constructed from the first async
  handler onward. See @spec-sync-prefix.
]

#gterm([`TypedEventBus<E>`])[
  A compile-time-restricted view over an `EventBus`. Enforces that `post` and
  `subscribe` only accept subtypes of `E`. Zero runtime overhead; delegates to the
  underlying bus. See @spec-typed and @design-typed.
]

#gterm([virtual threads])[
  Lightweight JDK threads (JEP 444, @jep444). The recommended executor choice for async
  dispatch. They block without consuming platform threads, eliminating the deadlock risk
  of bounded pools. See @edge-post-blocking.
]

#gterm([wildcard handler])[
  A handler registered via `subscribeAll` that fires for every posted event. Always
  synchronous. When placed after an async handler in priority order, runs on whichever
  thread completed the prior step. See @spec-wildcard, @design-wildcard, and
  @edge-wildcard-thread.
]
