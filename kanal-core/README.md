# kanal-core

The core event bus module. Kotlin-first, fully Java-compatible.

## Installation

```kotlin
repositories {
    maven("https://maven.meteordev.org/releases") {
        name = "meteordev"
    }
}

dependencies {
    implementation("io.github.big-iron-cheems:kanal-core:$VERSION")
}
```

Replace `$VERSION` with the latest version shown in the [root README](../README.md) badge.

## Features

- **Zero reflection on the hot path** - handlers are compiled to `Consumer<Event>` via `LambdaMetafactory` at
  subscription time; dispatch is a plain virtual call.
- **Supertype dispatch** - posting a `SubEvent` also reaches handlers registered for any superclass or interface in its
  hierarchy.
- **Priority ordering** - `Priority.HIGHEST` through `Priority.LOWEST`; equal-priority handlers fire in subscription
  order.
- **Cancellable events** - implement `Cancellable`; dispatch short-circuits as soon as any handler cancels.
- **Modifiable events** - implement `Modifiable<T>`; handlers read and replace a typed value during dispatch.
- **Wildcard listeners** - `bus.subscribeAll { e -> }` fires for every posted event, interleaved with typed handlers by
  priority.
- **Typed bus** - `bus.typed<NetworkEvent>()` returns a `TypedEventBus<NetworkEvent>` that restricts `post` and
  `subscribe` to subtypes of `NetworkEvent` at compile time.
- **Lambda subscribe** - `bus.subscribe<MyEvent> { e -> }` returns a `Subscription` token for removal; no annotation
  needed.
- **Java-friendly** - all public API accessible from Java; `EventBus.create()`,
  `TypedEventBusFactory.typed(bus, MyEvent.class)`.
- **Thread-safe** - `CopyOnWriteArrayList` per event type; safe for concurrent read / occasional write patterns.
- **Async dispatch** - opt-in per-handler async execution via `@Subscribe(async = true)` or
  `bus.subscribe<MyEvent>(async = true) { }`. An `Executor` (e.g. virtual threads) is supplied at bus construction time.
  `bus.postAsync(event)` returns a `CompletableFuture<T>` completing after all handlers finish.

## Quick start

```kotlin
class PlayerJumpEvent(val player: String) : Event

class MyListener {
    @Subscribe(priority = Priority.HIGH)
    fun onJump(e: PlayerJumpEvent) = println("${e.player} jumped!")
}

val bus = EventBus()
bus.subscribe(MyListener())
bus.post(PlayerJumpEvent("Steve"))

// Lambda style; returns a Subscription token
val sub = bus.subscribe<PlayerJumpEvent> { e -> println(e.player) }
sub.cancel()
```

## API reference

### Annotation subscribe

```kotlin
class MyListener {
    @Subscribe(priority = Priority.HIGH)
    fun onJump(e: PlayerJumpEvent) {
    }

    @Subscribe(async = true)
    fun onPacket(e: PacketReceived) {
    } // runs on executor thread
}

bus.subscribe(MyListener())
bus.unsubscribe(MyListener())

// Static / @JvmStatic methods
bus.subscribeStatic(MyStaticListener::class.java)
bus.unsubscribeStatic(MyStaticListener::class.java)
```

### Lambda subscribe

```kotlin
val sub = bus.subscribe<PlayerJumpEvent> { e -> println(e.player) }
val sub2 = bus.subscribe<PlayerJumpEvent>(Priority.HIGH) { e -> handle(e) }
val sub3 = bus.subscribe<PacketReceived>(async = true) { e -> handle(e) }

sub.cancel()        // removes this handler
sub.use { }         // AutoCloseable - cancels on block exit
```

### Cancellable events

```kotlin
class BlockBreakEvent(val block: String) : Event, Cancellable {
    override var isCancelled = false
}

bus.subscribe<BlockBreakEvent>(Priority.HIGH) { e -> e.cancel() }
bus.subscribe<BlockBreakEvent>(Priority.LOW) { /* never reached */ }

val result = bus.post(BlockBreakEvent("stone"))
println(result.isCancelled) // true
```

### Modifiable events

```kotlin
class DamageEvent(override var value: Double) : Event, Modifiable<Double>

bus.subscribe<DamageEvent>(Priority.HIGH) { e -> e.value *= 2.0 }
bus.subscribe<DamageEvent>(Priority.LOW) { e -> println(e.value) } // sees doubled value

val result = bus.post(DamageEvent(5.0)).value // 10.0
```

### Wildcard listeners

```kotlin
val audit = bus.subscribeAll(Priority.HIGHEST) { e ->
    println("[AUDIT] ${e::class.simpleName}")
}
val log = bus.subscribeAll(Priority.LOWEST) { e ->
    println("[LOG] ${e::class.simpleName}")
}

bus.isListeningAll() // true
audit.cancel()
log.cancel()
bus.isListeningAll() // false
```

### Typed bus

```kotlin
sealed interface NetworkEvent : Event
class PacketReceived(val bytes: ByteArray) : NetworkEvent
class ConnectionLost(val reason: String) : NetworkEvent

val networkBus = EventBus().typed<NetworkEvent>()
networkBus.subscribe<PacketReceived> { e -> handle(e.bytes) }
networkBus.post(PacketReceived(bytes))   // OK
// networkBus.post(ButtonClickEvent())   // compile error

// Access underlying bus
networkBus.delegate.unsubscribeAll()
```

### Async dispatch

```kotlin
val bus = EventBus(Executors.newVirtualThreadPerTaskExecutor())

// Per-handler async flag
bus.subscribe<PacketReceived>(async = true) { e -> handle(e) }

// postAsync - returns CompletableFuture
val future = bus.postAsync(PacketReceived(bytes))
future.thenAccept { e -> println("done: $e") }
future.join() // or block until complete
```

**Guarantees:**

- Priority ordering preserved across sync and async handlers.
- Mutation visibility guaranteed - lower-priority sync handlers observe mutations from higher-priority async handlers.
- Cancellation is automatically thread-safe across async handlers.
- `postAsync` never completes exceptionally due to handler errors; exceptions route to `exceptionHandler`.

### Java usage

```java
EventBus bus = EventBus.create();
bus.subscribe(new MyListener());
bus.post(new PlayerJumpEvent("Steve"));

// Lambda subscribe
Subscription sub = bus.subscribe(
    PlayerJumpEvent.class, Priority.NORMAL, e -> System.out.println(e.getPlayer())
);
sub.cancel();

// Async
EventBus asyncBus = EventBus.create(Executors.newVirtualThreadPerTaskExecutor());
CompletableFuture<PacketReceived> future = asyncBus.postAsync(new PacketReceived(bytes));
```

## Performance

Dispatch rows assume a warm cache. Subscribe rows measure warm-cache cost.
Benchmarked with JMH on JDK 25.

| Operation                   | Relative cost                   | Notes                                      |
|-----------------------------|---------------------------------|--------------------------------------------|
| `post`, 1 handler           | baseline                        | dominated by fixed bus overhead            |
| `post`, N handlers          | linear in N                     | marginal cost grows linearly               |
| Cancellation short-circuit  | flat, O(1)                      | independent of handler count               |
| Supertype dispatch          | same as exact-type              | `dispatchCache` eliminates BFS on hot path |
| `TypedEventBus` adapter     | same as plain `EventBus`        | delegation inlined by JIT                  |
| Cold subscribe (first time) | significantly higher            | `LambdaMetafactory` paid once per method   |
| Warm re-subscribe (cached)  | two orders of magnitude cheaper | invoker factory reused                     |

To run benchmarks:

```bash
./gradlew :kanal-core:jmh
./gradlew :kanal-core:jmh -PjmhInclude=PostThroughput
```

Results are written to `kanal-core/build/reports/jmh/results.json`.

## ABI stability

`kanal-core` uses [Kotlin ABI validation](https://github.com/Kotlin/binary-compatibility-validator).
The public API surface is tracked in `api/kanal-core.api`. Any binary-incompatible change
will fail the build via `./gradlew :kanal-core:checkKotlinAbi`.

Internal implementation classes under `io.github.bigironcheems.kanal.internal` are excluded
from the ABI surface and may change between releases.

## Examples

Runnable examples are in `src/examples/`:

| File                        | Covers                                                                                |
|-----------------------------|---------------------------------------------------------------------------------------|
| `BasicUsageExample`         | Annotation subscribers, lambda subscribe, cancellable, modifiable, error handling     |
| `WildcardExample`           | `subscribeAll`, priority interleaving, `isListeningAll`                               |
| `TypedBusExample`           | `TypedEventBus<E>`, sealed hierarchies, multi-bus, delegate access                    |
| `StaticAndSupertypeExample` | Static subscribers (`@JvmStatic`), supertype dispatch                                 |
| `AsyncExample`              | `postAsync`, annotation async handlers, blocking `post`, mixed dispatch, cancellation |
