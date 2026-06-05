#import "../theme.typ": *

= Dangerous Edges <edges>

This chapter collects the non-obvious runtime behaviours that are correct but will
cause silent failures or hard-to-diagnose bugs if you are not aware of them. Each
edge is stated as a precise description of what happens, followed by the conditions
under which it applies and how to avoid it.

== `post` blocks when async handlers are present <edge-post-blocking>

`post` is a synchronous call, but its behaviour changes when the bus has an executor
and the dispatch list contains at least one async handler. In that case, `post` blocks
the calling thread until the entire `CompletableFuture` chain completes.

This is intentional and documented (see @spec-post), but the implication on bounded
thread pools is a deadlock:

#warn(title: "Deadlock on bounded platform-thread pools.")[
  If `post` is called from a thread that belongs to the same bounded pool used as
  the bus's executor, the calling thread parks waiting for the chain. The chain needs
  a free pool thread to proceed. If the pool is exhausted, no thread is available and
  the call never returns.

  This scenario cannot occur with virtual-thread executors @jep444 because virtual
  threads do not hold a platform thread while parked.

  Mitigations:
  - Use `Executors.newVirtualThreadPerTaskExecutor()` as the executor (recommended).
  - Use `postAsync` instead of `post`; the caller decides when to wait.
  - Use a separate executor for the bus, distinct from the application's work pool.
]

== Wildcard handlers and thread-local state <edge-wildcard-thread>

Wildcard handlers registered via `subscribeAll` are always synchronous. When a wildcard
falls after an async handler in priority order it is inserted as a `thenRun` step in
the `CompletableFuture` chain. It runs on whichever thread completed the prior async
step -- not the posting thread.

#warn(title: "Thread-local state is not propagated across the chain.")[
  If your wildcard handler reads from `ThreadLocal`, MDC (Mapped Diagnostic Context),
  or any other thread-local mechanism, and your bus has async handlers at higher
  priority, the wildcard will see the state of the executor thread, not the posting
  thread. The posting thread's context is simply absent.

  This applies to:
  - SLF4J / Logback MDC
  - Spring Security `SecurityContextHolder`
  - Kotlin coroutine `ThreadLocal` elements not propagated via `asContextElement`
  - Any custom `ThreadLocal` populated on the posting thread

  To avoid this, either ensure your wildcard is registered at higher priority than all
  async handlers (so it runs in the sync prefix on the posting thread), or do not rely
  on thread-local state in wildcard handlers on buses with async dispatch.
]

== `post` vs `postAsync` error routing asymmetry <edge-error-asymmetry>

Handler exceptions are always routed to `exceptionHandler` in both `post` and
`postAsync`. But executor rejection (`RejectedExecutionException`) -- which occurs
when the executor's task queue is full or the executor has been shut down -- is
handled differently:

- `post`: rejection is caught and passed to `exceptionHandler`. `post` returns normally.
- `postAsync`: rejection causes the future to complete exceptionally with a
  `CompletionException`. The `exceptionHandler` is *not* called.

#warn(title: "postAsync silently swallows executor rejection if you discard the future.")[
  If you call `bus.postAsync(event)` and discard the returned `CompletableFuture`,
  executor rejection will be lost entirely -- no `exceptionHandler` call, no exception
  propagation, no log output. The event is silently not dispatched.

  Always either `.join()` or attach an `.exceptionally { }` / `.whenComplete { }` to
  futures returned by `postAsync` if you need to detect infrastructure failure.
]

The design rationale for this asymmetry is in @design-error-asymmetry.

== Override deduplication: unannotated override silently opts out <edge-override>

The bus scans declared methods for `@Subscribe`. Override deduplication is applied:
if a subclass declares a method with the same name and parameter types as a superclass
method, only the subclass version is seen. If the subclass override is not annotated
with `@Subscribe`, the handler is not registered -- even though the superclass version
was annotated.

```kotlin
open class Base {
    @Subscribe
    open fun on(e: MyEvent) { /* registered for Base instances */ }
}

class Sub : Base() {
    // No @Subscribe -- this override shadows Base.on and is NOT registered
    override fun on(e: MyEvent) { super.on(e) }
}

bus.subscribe(Sub())
bus.post(MyEvent()) // no handler fires
```

This is not a bug. It is @r4. But the failure mode is silent: the bus does not warn
that a handler was expected but not found.

*Fix*: re-annotate every override that should participate in dispatch.

```kotlin
class Sub : Base() {
    @Subscribe  // required
    override fun on(e: MyEvent) { super.on(e) }
}
```

== `asFlow` and `nextEvent` subscription timing window <edge-timing>

Covered in detail in @coroutines-timing. The short version:

In a `runBlocking` + `launch` context, events posted after `launch` but before the
collector coroutine body executes are silently lost. Use `Dispatchers.Unconfined` to
force the coroutine body to execute eagerly before `launch` returns. This is not needed
in production coroutine scopes.

`suspendHandler` does not have this issue (@c1).

== `suspendHandler` default scope and in-flight cancellation <edge-suspendhandler-scope>

When `suspendHandler` is called without an explicit scope, it creates an internal
`CoroutineScope`. Calling `sub.cancel()` cancels that scope, which immediately
cancels any handler coroutines that are currently executing.

If your handler body is mid-way through a suspend operation (a network call, a database
write, a `delay`) when `sub.cancel()` is called, that coroutine receives a
`CancellationException`. The operation is abandoned without completing.

This is the expected structured concurrency behaviour, but it is easy to forget when
using the convenient no-scope default. If your handler does work that must not be
interrupted, use a scope that you control and supervise:

```kotlin
val sub = bus.suspendHandler<MyEvent>(scope = supervisedScope) { e ->
    criticalWork(e) // not interrupted when sub.cancel() is called
}
sub.cancel() // removes the subscription; supervisedScope is unaffected
```

See @c4 for the formal contract and @design-suspendhandler-scope for the bug that
motivated the current design.

== `unsubscribeAll` does not interrupt in-flight async dispatch <edge-unsubscribeall>

`unsubscribeAll()` clears all handler lists and invalidates the dispatch cache. But any
`post` call that was already in progress when `unsubscribeAll()` was called will
continue to completion using the snapshot it captured at the start of dispatch (COWAL
snapshot semantics, see @s2).

Handlers that were registered at the time `post` began executing may still execute
after `unsubscribeAll()` returns. There is no mechanism to interrupt a dispatch in
progress.

This is the correct behaviour for concurrent data structures, but can be surprising
when shutting down a component that uses the bus.
