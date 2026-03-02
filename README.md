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

## Publishing

### Meteor Maven

Kanal is published to [Meteor Maven](https://maven.meteordev.org/). Consumers can use it by adding the repository to their project:

```kotlin
repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
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
