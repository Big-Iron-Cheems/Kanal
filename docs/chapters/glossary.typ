#import "../theme.typ": *

= Glossary <glossary>

Quick reference for terms used throughout this manual.

#gterm([`async` flag])[
  A boolean on `@Subscribe` and lambda `subscribe` overloads. When `true` and an executor
  is configured on the bus, the handler runs on the executor rather than the posting
  thread. Without an executor the flag is treated as a hint and the handler runs
  synchronously. See @async-enabling.
]

#gterm([Cancellable])[
  An interface a event class may implement to support mid-chain cancellation. Once any
  handler sets `isCancelled = true`, all lower-priority handlers are skipped. A plain
  `var isCancelled = false` field is sufficient; no atomic or volatile declaration is
  needed. See @spec-cancellable and @async-cancellation.
]

#gterm([dispatch cache])[
  A `ConcurrentHashMap` keyed by the concrete runtime type of the posted event. Stores
  the pre-sorted, pre-merged handler list for that type so that subsequent posts of the
  same type skip the supertype BFS walk entirely. Invalidated on every subscribe or
  unsubscribe call. See @design-cache.
]

#gterm([dispatch chain])[
  A `CompletableFuture` pipeline built when at least one handler in the dispatch list
  carries `async = true`. Async steps use `thenApplyAsync`; sync steps after an async
  step use `thenApply`. The chain is built eagerly before any step executes, so priority
  order is determined on the posting thread. See @async-chain.
]

#gterm([event])[
  Any class that implements the `Event` marker interface. No base class, no registration,
  and no annotation on the class itself is required. Plain classes, data classes, sealed
  hierarchies, and Java records are all valid. See @overview-concepts.
]

#gterm([`EventBus`])[
  The primary API type. Manages handler registration, dispatch, and lifecycle. The
  default implementation is `SimpleEventBus`. Async support is enabled by passing an
  executor at construction. See @spec-post.
]

#gterm([`exceptionHandler`])[
  A `(Throwable) -> Unit` callback configured on the bus (defaults to printing the stack
  trace). Handler exceptions are always routed here rather than propagating out of `post`
  or completing `postAsync` exceptionally. See D5 in @spec-dispatch.
]

#gterm([executor])[
  A `java.util.concurrent.Executor` configured at bus construction time. Used to run
  handlers marked `async = true`. Virtual-thread executors
  (`Executors.newVirtualThreadPerTaskExecutor()`) are recommended. See @async-enabling
  and @jep444.
]

#gterm([`LambdaMetafactory`])[
  A JDK API used internally to turn `@Subscribe`-annotated methods into `Consumer<Event>`
  instances at subscription time. The generated consumer is a hidden class that calls
  the target method directly, making dispatch equivalent to a direct call after JIT
  inlining. See @design-lambdametafactory and @lambdametafactory.
]

#gterm([`Modifiable<T>`])[
  An interface a event class may implement to expose a mutable `value: T` field. Each
  handler in the chain observes writes from all higher-priority handlers that already
  completed. A plain `var value` field is sufficient; no synchronisation is required.
  See @spec-modifiable.
]

#gterm([priority])[
  A plain `Int` attached to every handler. Higher values fire first. Five named constants
  are provided in `Priority`: `HIGHEST` (200) through `LOWEST` (-200). Any integer is
  valid. See @spec-priority.
]

#gterm([subscription])[
  A token returned by lambda-based registration. Calling `cancel()` removes the handler
  from the bus. `cancel()` is idempotent. See @spec-registration.
]

#gterm([sync prefix optimisation])[
  When the dispatch list begins with sync handlers before the first async handler, those
  leading handlers run directly on the calling thread with no `CompletableFuture`
  allocated. The chain is only constructed at the first async handler. Buses with no
  async handlers incur zero `CompletableFuture` overhead. See @spec-sync-prefix.
]

#gterm([`TypedEventBus<E>`])[
  A compile-time-restricted view over an `EventBus`. `post` and `subscribe` only accept
  subtypes of `E`, giving a type-safe API for sealed event hierarchies. No separate
  handler list; it delegates to the underlying bus. See @spec-typed and @design-typed.
]

#gterm([virtual threads])[
  Lightweight JDK threads introduced in JEP 444 (@jep444). The recommended executor
  choice for async dispatch because they block without consuming platform threads,
  eliminating the deadlock risk that bounded platform-thread pools carry. See the warning
  in @spec-post.
]

#gterm([wildcard handler])[
  A handler registered via `subscribeAll` that fires for every posted event regardless
  of type. Always synchronous; no `async` flag. When placed after an async handler in
  priority order it runs on whichever thread completed the prior step. See @spec-wildcard
  and @design-wildcard.
]

