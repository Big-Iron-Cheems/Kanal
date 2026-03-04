# Kanal

[![CI][badge-ci]][link-ci]
[![Kotlin][badge-kotlin]][link-kotlin]
[![JVM][badge-jvm]][link-jvm]
[![Maven Central][badge-maven]][link-maven]
[![License][badge-license]][link-license]

A **Kotlin-first, Java-compatible** event-handler library targeting **JDK 25**.

## Features

* **Zero reflection on the hot path**; handlers are compiled to `Consumer<Event>` via
  `LambdaMetafactory` at subscription time; dispatch is a plain virtual call.
* **Supertype dispatch**; posting a `SubEvent` also reaches handlers registered for any
  superclass or interface in its hierarchy.
* **Priority ordering**; `Priority.HIGHEST` through `Priority.LOWEST`; equal-priority
  handlers fire in subscription order.
* **Cancellable events**; implement `Cancellable`; dispatch short-circuits as soon as any
  handler cancels.
* **Modifiable events**; implement `Modifiable<T>`; handlers read and replace a typed value
  during dispatch.
* **Wildcard listeners**; `bus.subscribeAll { e -> }` fires for every posted event,
  interleaved with typed handlers by priority.
* **Typed bus**; `bus.typed<NetworkEvent>()` returns a `TypedEventBus<NetworkEvent>` that
  restricts `post` and `subscribe` to subtypes of `NetworkEvent` at compile time.
* **Lambda subscribe**; `bus.subscribe<MyEvent> { e -> }` returns a `Subscription` token
  for removal; no annotation needed.
* **Java-friendly**; all public API accessible from Java; `EventBus.create()`,
  `TypedEventBusFactory.typed(bus, MyEvent.class)`.
* **Thread-safe**; `CopyOnWriteArrayList` per event type; safe for concurrent read /
  occasional write patterns.
* **Async dispatch**; opt-in per-handler async execution via `@Subscribe(async = true)` or
  `bus.subscribe<MyEvent>(async = true) { }`. An `Executor` (e.g. virtual threads) is supplied
  at bus construction time; handlers without an executor fall back to synchronous execution
  without throwing. `bus.postAsync(event)` returns a `CompletableFuture<T>` completing after
  all handlers finish; priority ordering and mutation visibility are preserved.

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

See [`src/examples/kotlin`](src/examples/kotlin/io/github/bigironcheems/kanal/examples)
for executable examples covering all features (each file has a `main()`):

| File                           | Covers                                                                            |
|--------------------------------|-----------------------------------------------------------------------------------|
| `BasicUsageExample.kt`         | Annotation subscribers, lambda subscribe, cancellable, modifiable, error handling |
| `WildcardExample.kt`           | `subscribeAll`, priority interleaving, `isListeningAll`                           |
| `TypedBusExample.kt`           | `TypedEventBus<E>`, sealed hierarchies, multi-bus, delegate access                |
| `StaticAndSupertypeExample.kt` | Static subscribers (`@JvmStatic`), supertype dispatch                             |

## Java usage

```java
EventBus bus = EventBus.create();
bus.subscribe(new MyListener());
bus.post(new PlayerJumpEvent("Steve"));

// Lambda subscribe
Subscription sub = bus.subscribe(
    PlayerJumpEvent.class, Priority.NORMAL, e -> System.out.println(e.getPlayer())
);
sub.cancel();

// Wildcard
bus.subscribeAll(Priority.NORMAL, e -> System.out.println(e));

// Typed bus
TypedEventBus<NetworkEvent> networkBus = TypedEventBusFactory.typed(bus, NetworkEvent.class);
networkBus.post(new PacketReceived(bytes));
```

## Async dispatch

Supply an `Executor` at construction time to enable async handler dispatch.
Virtual threads (JDK 21+) are the recommended choice:

```kotlin
val bus = EventBus(Executors.newVirtualThreadPerTaskExecutor())
```

Mark individual handlers async with the annotation flag or the lambda parameter:

```kotlin
// Annotation style
class PacketHandler {
    @Subscribe(async = true)
    fun onPacket(e: PacketReceived) { /* runs on virtual thread */ }
}

// Lambda style
val sub = bus.subscribe<PacketReceived>(async = true) { e -> handle(e) }
```

Use `postAsync` to get a `CompletableFuture` that completes when all handlers finish:

```kotlin
val future: CompletableFuture<PacketReceived> = bus.postAsync(PacketReceived(bytes))
future.thenAccept { e -> println("all handlers done, event: $e") }

// Or await it blocking
val event = bus.postAsync(PacketReceived(bytes)).join()
```

**Semantic guarantees:**
- Priority ordering is preserved; handlers execute in priority order regardless of async flag.
- Mutation visibility is guaranteed; a lower-priority sync handler always observes mutations
  from higher-priority async handlers (the chain drains before each sync step).
- Cancellation works automatically and is thread-safe. No `@Volatile` or `AtomicBoolean`
  required on your `isCancelled` field. The bus wraps cancellation in an `AtomicBoolean` for
  the duration of the async chain and writes the result back to the event once all handlers
  complete. A plain `var isCancelled = false` is sufficient.
- `postAsync` never completes exceptionally due to handler errors; exceptions route to the
  bus's `exceptionHandler`. Only infrastructure failure (executor rejection) is exceptional.
- No executor configured: `async = true` handlers fall back to synchronous execution silently.

**Java:**

```java
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
EventBus bus = EventBus.create(executor);

// Subscription with async flag
bus.subscribe(PacketReceived.class, Priority.NORMAL, true, e -> handle(e));

// postAsync
CompletableFuture<PacketReceived> future = bus.postAsync(new PacketReceived(bytes));
future.thenAccept(e -> System.out.println("done"));
```

## Performance

Dispatch rows assume a warm cache with the dispatch list already built.
Subscribe rows measure first-call (cold) and subsequent-call (warm) cost.

Benchmarked with JMH on JDK 25.

| Operation                      | Relative cost                   | Notes                                           |
|--------------------------------|---------------------------------|-------------------------------------------------|
| `post, 1 handler`              | baseline                        | dominated by fixed bus overhead                 |
| `post, N handlers`             | linear in N                     | marginal cost grows linearly with handler count |
| Cancellation short-circuit     | flat, O(1)                      | independent of registered handler count         |
| Supertype / interface dispatch | same as exact-type              | `dispatchCache` eliminates BFS on hot path      |
| `TypedEventBus` adapter        | same as plain `EventBus`        | delegation inlined by JIT                       |
| Cold subscribe (first time)    | significantly higher            | `LambdaMetafactory` paid once per method        |
| Warm re-subscribe (cached)     | two orders of magnitude cheaper | invoker factory reused                          |

## Running benchmarks

Benchmarks use [JMH](https://github.com/openjdk/jmh) via the
[gradle-jmh-plugin](https://github.com/melix/jmh-gradle-plugin).

Run all benchmarks:

```
./gradlew jmh
```

Run a specific benchmark class (substring match on the fully-qualified name):

```
./gradlew jmh -PjmhInclude=PostThroughput
```

Results are written as JSON to:

* All benchmarks: `build/reports/jmh/results.json`
* Filtered run: `build/reports/jmh/results-<filter>.json`

Available benchmark classes in `src/jmh/kotlin/.../bench/`:

| Class                           | What it measures                                                   |
|---------------------------------|--------------------------------------------------------------------|
| `PostThroughputBenchmark`       | Warm `post` throughput vs. a direct method call baseline           |
| `CancellablePostBenchmark`      | Short-circuit cost when an event is cancelled at the first handler |
| `SubscribeUnsubscribeBenchmark` | Annotation-based subscribe/unsubscribe cost; instance and static   |
| `LambdaSubscribeBenchmark`      | Lambda subscribe cost; raw bus vs typed bus adapter                |
| `SupertypeDispatchBenchmark`    | Supertype / interface hierarchy dispatch vs. exact-type            |
| `ColdDispatchBenchmark`         | First-post cost before `dispatchCache` is populated                |
| `TypedEventBusBenchmark`        | `TypedEventBus` adapter overhead vs. plain `EventBus`              |
| `WildcardPostBenchmark`         | Wildcard-only, typed-only, and mixed dispatch cost                 |
| `AsyncDispatchBenchmark`        | `postAsync` latency vs sync `post`; all-async, all-sync, mixed     |

## Publishing

### Meteor Maven

Kanal is published to [Meteor Maven](https://maven.meteordev.org/). Consumers can use it by adding the repository to their project:

```kotlin
repositories {
    maven("https://maven.meteordev.org/releases") {
        name = "meteordev"
    }
}

dependencies {
    implementation("io.github.big-iron-cheems:kanal:0.1.0")
}
```

### Local (Maven Local)

Publish to your local Maven repository for testing as a dependency in another project:

```
./gradlew publishToMavenLocal
```

The artifact is installed to `~/.m2/repository/io/github/big-iron-cheems/kanal/`.

Add it as a dependency in a consumer project:

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    implementation("io.github.big-iron-cheems:kanal:0.1.0")
}
```

### Maven Central / JitPack

> **Not yet published.** Maven Central publishing is stubbed in `build.gradle.kts`
> (the repository block is commented out) and will be wired up before the first public release.

## License

Apache 2.0, see [LICENSE](LICENSE).

[//]: # (Badge definitions)
[badge-ci]: https://github.com/Big-Iron-Cheems/Kanal/actions/workflows/ci.yml/badge.svg
[badge-kotlin]: https://img.shields.io/badge/kotlin-2.1-7F52FF?logo=kotlin&logoColor=white
[badge-jvm]: https://img.shields.io/badge/JVM-25-orange?logo=openjdk&logoColor=white
[badge-maven]: https://img.shields.io/maven-central/v/io.github.big-iron-cheems/kanal?logo=apachemaven&logoColor=white
[badge-license]: https://img.shields.io/github/license/Big-Iron-Cheems/Kanal?logo=apache&logoColor=white

[//]: # (Link definitions)
[link-ci]: https://github.com/Big-Iron-Cheems/Kanal/actions/workflows/ci.yml
[link-kotlin]: https://kotlinlang.org
[link-jvm]: https://openjdk.org/projects/jdk/25/
[link-maven]: https://central.sonatype.com/artifact/io.github.big-iron-cheems/kanal
[link-license]: https://github.com/Big-Iron-Cheems/Kanal/blob/main/LICENSE
