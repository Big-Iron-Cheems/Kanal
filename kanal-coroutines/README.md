# kanal-coroutines

Kotlin coroutines extensions for `kanal-core`. Kotlin-only - no Java API surface.

## Installation

Depends on `kanal-core` automatically via `api()`. No need to declare both.

```kotlin
repositories {
    maven("https://maven.meteordev.org/releases") {
        name = "meteordev"
    }
}

dependencies {
    implementation("io.github.big-iron-cheems:kanal-coroutines:$VERSION")
}
```

Replace `$VERSION` with the latest version shown in the [root README](../README.md) badge.

## Features

- **`asFlow`** - expose any event type as a `Flow<T>`; subscription lifecycle tied to the collector.
- **`postSuspend`** - dispatch events from a coroutine context with configurable dispatcher.
- **`suspendHandler`** - register suspend lambda handlers with `Parallel`, `DiscardIfBusy`, or `ReplaceLatest`
  concurrency behaviour.
- **`nextEvent`** - suspend until the next matching event arrives; single-shot, unregisters automatically.
- **`nextEventOrNull`** - like `nextEvent` with a timeout; returns `null` if no event arrives in time.
- **`TypedEventBus` support** - all extensions available on `TypedEventBus<E>` with the same type safety guarantees.

## Quick start

```kotlin
val bus = EventBus()

// Collect events as a Flow
bus.asFlow<PlayerJumpEvent>()
    .collect { e -> println("${e.player} jumped") }

// Wait for a single event
val event = bus.nextEvent<PlayerJumpEvent>()

// Register a suspend handler
val sub = bus.suspendHandler<PlayerJumpEvent> { e ->
    delay(100)
    println("${e.player} jumped after delay")
}
sub.cancel()
```

## API reference

### `asFlow`

Returns a `Flow<T>` that emits every event of type `T` posted to the bus.
The subscription is registered when collection starts and removed when the collector is cancelled.

```kotlin
bus.asFlow<PlayerJumpEvent>()
    .filter { it.player == "Steve" }
    .collect { e -> println(e.player) }

// With priority
bus.asFlow<PlayerJumpEvent>(Priority.HIGH)
    .collect { e -> handle(e) }

// TypedEventBus
val networkBus = EventBus().typed<NetworkEvent>()
networkBus.asFlow<PacketReceived>()
    .collect { e -> handle(e.bytes) }
```

### `postSuspend`

Dispatches an event from a coroutine context. Suspends until all handlers finish.
Defaults to the caller's coroutine context - no thread hop unless an explicit context is provided.

```kotlin
launch {
    bus.postSuspend(PlayerJumpEvent("Steve")) // stays on caller's dispatcher
}

// Shift to a specific dispatcher
bus.postSuspend(PlayerJumpEvent("Steve"), Dispatchers.IO)
```

Use `postSuspend` when you need explicit dispatcher control. For most cases
`EventBus.post` is sufficient.

### `suspendHandler`

Registers a suspend lambda handler and returns a `Subscription` token.
Controls concurrent execution via `SuspendHandlerBehaviour`.

```kotlin
// Parallel - new coroutine per event (default)
val sub = bus.suspendHandler<PacketReceived> { e ->
    delay(100)
    handle(e)
}
sub.cancel()

// DiscardIfBusy - skip if previous handler still running
bus.suspendHandler<PacketReceived>(
    behaviour = SuspendHandlerBehaviour.DiscardIfBusy
) { e -> handle(e) }

// ReplaceLatest - cancel previous, launch new
bus.suspendHandler<PacketReceived>(
    behaviour = SuspendHandlerBehaviour.ReplaceLatest
) { e -> handle(e) }

// With custom scope
bus.suspendHandler<PacketReceived>(
    scope = viewModelScope
) { e -> handle(e) }

// TypedEventBus
val networkBus = EventBus().typed<NetworkEvent>()
networkBus.suspendHandler<PacketReceived> { e -> handle(e) }
```

**Behaviour comparison:**

| Behaviour       | Use case                        | Cost                        |
|-----------------|---------------------------------|-----------------------------|
| `Parallel`      | Independent concurrent handlers | Coroutine launch per event  |
| `DiscardIfBusy` | UI updates, debouncing          | Atomic check only when busy |
| `ReplaceLatest` | Always process latest event     | Cancel + relaunch           |

### `nextEvent`

Suspends until the next matching event arrives. Single-shot - resumes once and unregisters.

```kotlin
val packet = bus.nextEvent<PacketReceived>()

// With predicate
val large = bus.nextEvent<PacketReceived> { it.bytes.size > 1024 }

// With priority
val packet = bus.nextEvent<PacketReceived>(priority = Priority.HIGH)

// TypedEventBus
val networkBus = EventBus().typed<NetworkEvent>()
val packet = networkBus.nextEvent<PacketReceived>()
```

### `nextEventOrNull`

Like `nextEvent` but returns `null` if the timeout elapses before a matching event arrives.

```kotlin
val packet = bus.nextEventOrNull<PacketReceived>(5.seconds)
if (packet != null) handle(packet) else println("timed out")

// With predicate
val packet = bus.nextEventOrNull<PacketReceived>(5.seconds) { it.bytes.size > 1024 }

// TypedEventBus
val networkBus = EventBus().typed<NetworkEvent>()
val packet = networkBus.nextEventOrNull<PacketReceived>(5.seconds)
```

### Choosing the right API

| Scenario                         | Recommended API   |
|----------------------------------|-------------------|
| Stream of events over time       | `asFlow`          |
| Post from a coroutine            | `postSuspend`     |
| Handle events with suspend logic | `suspendHandler`  |
| Wait for one specific event      | `nextEvent`       |
| Wait for one event with timeout  | `nextEventOrNull` |

## Performance

Benchmarked with JMH on JDK 25. All coroutine benchmarks include `runBlocking` bridge cost.

| Operation                                | Cost          | Notes                                     |
|------------------------------------------|---------------|-------------------------------------------|
| `postToActiveFlow`                       | ~120 ns/event | +`trySend` channel overhead vs raw `post` |
| `asFlow` setup + cancel                  | ~4.5 us       | Fixed Flow subscription lifecycle cost    |
| `suspendHandler` (`DiscardIfBusy`)       | ~35 ns        | Atomic check only                         |
| `suspendHandler` (`Parallel`)            | ~363 ns       | Coroutine launch per event                |
| `suspendHandler` (`ReplaceLatest`)       | ~1.4 us       | Cancel + relaunch                         |
| `postSuspend` (`Dispatchers.Unconfined`) | ~260 ns       | No thread hop                             |
| `postSuspend` (`Dispatchers.Default`)    | ~17 us        | Thread hop dominates                      |
| `postSuspend` (default, no hop)          | ~same as post | EmptyCoroutineContext, no thread switch   |
| `nextEvent` end-to-end                   | ~164 us       | Coroutine scheduler round-trip            |
| `TypedEventBus` adapter overhead         | ~0%           | Delegation inlined by JIT                 |

To run benchmarks:

```
./gradlew :kanal-coroutines:jmh
./gradlew :kanal-coroutines:jmh -PjmhInclude=NextEvent
```

Results are written to `kanal-coroutines/build/reports/jmh/results.json`.

## ABI stability

`kanal-coroutines` uses [Kotlin ABI validation](https://github.com/Kotlin/binary-compatibility-validator).
The public API surface is tracked in `api/kanal-coroutines.api`. Any binary-incompatible change
will fail the build via `./gradlew :kanal-coroutines:checkLegacyAbi`.

Internal implementation classes are excluded from the ABI surface and may change between releases.

## Examples

Runnable examples are in `src/examples/kotlin/`:

| File                    | Covers                                                                                            |
|-------------------------|---------------------------------------------------------------------------------------------------|
| `AsFlowExample`         | Basic collection, Flow operators, priority, subscription lifetime, TypedEventBus                  |
| `SuspendHandlerExample` | Parallel, DiscardIfBusy, ReplaceLatest behaviours, priority, cancellation, TypedEventBus          |
| `NextEventExample`      | Basic await, type filtering, predicate, priority, single-shot, timeout, TypedEventBus             |
| `PostSuspendExample`    | Basic usage, custom dispatcher, Unconfined, cancellable events, concurrent posting, TypedEventBus |
