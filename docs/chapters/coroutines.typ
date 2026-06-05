#import "../theme.typ": *

= Coroutines Integration <coroutines>

The `kanal-coroutines` module provides Kotlin coroutines @kotlinx-coroutines extensions
for `EventBus` and `TypedEventBus`. For API signatures, usage examples, and installation,
see the module README and KDocs. This chapter covers the three non-obvious behaviours
that cannot be expressed in method-level documentation.

All formal contracts for this module are in @spec-coroutines (@c1 through @c5).

== API selection <coroutines-choosing>

The choice between `suspendHandler`, `asFlow`, and `nextEvent` has correctness
implications beyond style preference.

#table(
  columns: (auto, 1fr, auto),
  stroke: 0.5pt + luma(200),
  inset: (x: 0.8em, y: 0.55em),
  fill: (_, row) => if calc.odd(row) { luma(248) } else { white },
  table.header(
    [*API*], [*When to use*], [*Subscription timing*],
  ),
  [`suspendHandler`],  [Suspend logic per event. Most common.],               [Synchronous -- always safe (@c1).],
  [`asFlow`],          [Flow operators needed (filter, take, merge, ...).],    [Inside collector body -- see @coroutines-timing.],
  [`nextEvent`],       [Wait for one specific event, then continue.],          [Inside coroutine body -- see @coroutines-timing.],
  [`nextEventOrNull`], [Like `nextEvent` with a bounded wait.],                [Inside coroutine body -- see @coroutines-timing.],
  [`postSuspend`],     [Posting from a coroutine with dispatcher control.],    [N/A -- posting, not subscribing.],
)

When in doubt, use `suspendHandler`. It is the only option with no subscription timing
concerns (@c1).

== Subscription timing window <coroutines-timing>

`asFlow` and `nextEvent` register their subscriptions *inside the coroutine body*, at
the first suspension point. This means there is a window between when the coroutine is
*launched* and when the subscription is *active*. Events posted during that window are
silently lost.

This is not a bug -- it is an inherent consequence of how `callbackFlow`
@callbackflow and `suspendCancellableCoroutine` @cancellablecontinuation work. The
subscription cannot be registered before the coroutine body executes, and the coroutine
body does not execute until the scheduler runs it.

*When it matters.* In a `runBlocking` + `launch` context, `launch` schedules the child
coroutine but does not run it. Code after the `launch` call executes before the
coroutine body starts. Any `post` call in that code arrives before the subscription is
registered.

*When it does not matter.* In a real coroutine scope that is already executing
(viewModelScope, lifecycleScope, a coroutine under `runTest` + `advanceUntilIdle`), the
scheduler will run the collector before the next `post` at any normal suspension point.
No special handling is needed in production code.

*The fix for runBlocking contexts.* Use `Dispatchers.Unconfined` when launching the
collector. `Unconfined` runs the coroutine eagerly on the current thread until its first
suspension point, guaranteeing the subscription is registered before `launch` returns:

```kotlin
// runBlocking context only -- not needed in production scopes
val job = launch(Dispatchers.Unconfined) {
    bus.asFlow<MyEvent>().take(N).collect { handle(it) }
}
bus.post(MyEvent()) // safe: subscription registered before this line
```

The same pattern applies to `nextEvent`:

```kotlin
val job = launch(Dispatchers.Unconfined) {
    val event = bus.nextEvent<MyEvent>()
    handle(event)
}
bus.post(MyEvent()) // safe
```

`suspendHandler` does not need this pattern because it registers synchronously (@c1).

== `nextEvent` concurrent-resume safety <coroutines-nextevent-safety>

`nextEvent` uses `suspendCancellableCoroutine`. A naive implementation guards the
resume with an `isActive` check:

```kotlin
// Unsafe
val sub = subscribe<T>(priority) { event ->
    if (!predicate(event)) return@subscribe
    if (!cont.isActive) return@subscribe  // check
    cont.resume(event)                    // resume -- not atomic with check
}
```

On a bus with concurrent posters, two matching events arriving on different threads can
both pass the `isActive` check before either calls `cont.resume`. The second call to
`cont.resume` throws `IllegalStateException` because `CancellableContinuation` does not
permit concurrent resumes.

The implementation avoids this with an `AtomicBoolean` claimed flag:

```kotlin
val claimed = AtomicBoolean(false)
val sub = subscribe<T>(priority) { event ->
    if (!predicate(event)) return@subscribe
    if (claimed.compareAndSet(false, true)) cont.resume(event)
}
cont.invokeOnCancellation { sub.cancel() }
```

`compareAndSet(false, true)` is atomic. Exactly one thread wins regardless of
concurrency. All subsequent arrivals find `claimed` already true and return immediately.
Cancellation unregisters the subscription via `invokeOnCancellation`. See @c2 for the
formal guarantee and @design-nextevent for the full analysis.

== `suspendHandler` scope ownership <coroutines-scope-ownership>

The `scope` parameter controls which `CoroutineScope` is used to launch handler
coroutines, and -- critically -- what happens when `sub.cancel()` is called.

When `scope` is `null` (the default), `suspendHandler` creates an internal scope.
`sub.cancel()` cancels that scope, which cancels all in-flight handler coroutines for
this subscription. This is the safe default for isolated, short-lived handlers.

When an explicit scope is provided, `sub.cancel()` removes the subscription from the
bus but *does not touch the scope*. The caller owns the scope lifecycle.

#warn(title: "Providing an application scope without understanding this contract.")[
  If you call `bus.suspendHandler<T>(scope = viewModelScope) { ... }` and then call
  `sub.cancel()`, your `viewModelScope` is *not* cancelled. This is correct.

  But if you call `bus.suspendHandler<T> { ... }` (no scope) and later call
  `sub.cancel()`, the internal scope *is* cancelled. Any in-flight handler coroutines
  are interrupted. If your handler was mid-way through a long operation, that work is
  cancelled without warning.

  For long-lived handlers in components with defined lifecycles, always provide the
  component's scope explicitly. The internal scope is convenient for scripts and tests,
  not for production component code.
]

The early-version bug that motivated this design -- where `sub.cancel()` destroyed
`viewModelScope` -- is documented in @design-suspendhandler-scope. The formal contract
is @c4.
