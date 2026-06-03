#import "../theme.typ": *

= Quick Start <quickstart>

This chapter shows the most common patterns in minimal working code. Full contracts
and invariants are in @spec.

== Installation <quickstart-install>

Add the dependency from the Meteor Maven repository:

```kotlin
// build.gradle.kts
repositories {
    maven("https://maven.meteordev.org/releases")
}
dependencies {
    implementation("io.github.big-iron-cheems:kanal:0.2.0")
}
```

== Basic subscribe and post <quickstart-basic>

```kotlin
// 1. Any class that implements Event can be dispatched.
class PlayerJumpEvent(val player: String) : Event

// 2. Create a bus.
val bus = EventBus()

// 3. Register a lambda handler and hold the token for later removal.
val sub = bus.subscribe<PlayerJumpEvent> { e ->
    println("${e.player} jumped")
}

// 4. Post an event. All registered handlers run before post() returns.
bus.post(PlayerJumpEvent("Alice"))  // prints: Alice jumped

// 5. Remove the handler.
sub.cancel()
```

Java:
```java
EventBus bus = EventBus.create();
Subscription sub = bus.subscribe(
    PlayerJumpEvent.class, Priority.NORMAL, e -> System.out.println(e.getPlayer() + " jumped")
);
bus.post(new PlayerJumpEvent("Alice"));
sub.cancel();
```

== Annotation-based handlers <quickstart-annotation>

```kotlin
class MyListener {
    @Subscribe(priority = Priority.HIGH)
    fun onJump(e: PlayerJumpEvent) {
        println("high-priority: ${e.player}")
    }

    @Subscribe(priority = Priority.LOW)
    fun onJumpLow(e: PlayerJumpEvent) {
        println("low-priority: ${e.player}")
    }
}

val listener = MyListener()
bus.subscribe(listener)
bus.post(PlayerJumpEvent("Bob"))
// high-priority: Bob
// low-priority: Bob
bus.unsubscribe(listener)
```

== Cancellable events <quickstart-cancellable>

```kotlin
class LoginEvent(val user: String) : Event, Cancellable {
    override var isCancelled = false
}

// High-priority guard: cancel if user is banned.
bus.subscribe<LoginEvent>(Priority.HIGH) { e ->
    if (isBanned(e.user)) e.cancel()
}

// Low-priority handler: only runs if not cancelled.
bus.subscribe<LoginEvent>(Priority.LOW) { e ->
    grantAccess(e.user)
}

val event = bus.post(LoginEvent("mallory"))
if (event.isCancelled) println("login blocked")
```

== Modifiable events <quickstart-modifiable>

```kotlin
class DamageEvent(override var value: Double) : Event, Modifiable<Double>

// Halve damage at NORMAL priority.
bus.subscribe<DamageEvent> { e -> e.value *= 0.5 }

val result = bus.post(DamageEvent(100.0)).value  // 50.0
```

== Scoped subscriptions <quickstart-scoped>

Use `use { }` (Kotlin) or try-with-resources (Java) to automatically cancel a handler
when a block exits:

```kotlin
bus.subscribe<TickEvent> { tick() }.use {
    runGameLoop()
}
// Handler is cancelled after runGameLoop() returns.
```

```java
try (Subscription sub = bus.subscribe(TickEvent.class, Priority.NORMAL, e -> tick())) {
    runGameLoop();
}
```

== Typed bus <quickstart-typed>

Restrict a bus to a sealed hierarchy for compile-time safety:

```kotlin
sealed interface NetworkEvent : Event
class PacketReceived(val bytes: ByteArray) : NetworkEvent
class ConnectionLost(val reason: String) : NetworkEvent

val net = EventBus().typed<NetworkEvent>()
net.post(PacketReceived(bytes))    // OK
// net.post(PlayerJumpEvent("x")) // compile error
```

== Async dispatch <quickstart-async>

Opt individual handlers into async execution on a virtual-thread executor:

```kotlin
val bus = EventBus(Executors.newVirtualThreadPerTaskExecutor())

bus.subscribe<PacketReceived>(async = true) { e ->
    processPacket(e.bytes)  // runs on a virtual thread
}

// postAsync: returns immediately, complete callback when done.
bus.postAsync(PacketReceived(bytes)).thenAccept {
    println("all handlers done")
}

// post: blocks until all handlers (including async) complete.
bus.post(PacketReceived(bytes))
```

See @async for the full contract, chain semantics, and interaction with `Cancellable`.
