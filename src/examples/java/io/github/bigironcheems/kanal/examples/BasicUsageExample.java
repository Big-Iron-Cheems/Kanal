package io.github.bigironcheems.kanal.examples;

import io.github.bigironcheems.kanal.*;

/**
 * Demonstrates core Java-facing usage: annotation subscribers, lambda subscribe,
 * Subscription as AutoCloseable, Cancellable, Modifiable, and custom error handlers.
 * <p>
 * Consumer-based subscribe overloads are used throughout; no Function1/Unit boilerplate needed.
 */
public class BasicUsageExample {

    // Event definitions

    static class PlayerJumpEvent implements Event {
        final String player;

        PlayerJumpEvent(String player) {
            this.player = player;
        }
    }

    static class BlockBreakEvent implements Event, Cancellable {
        final String block;
        private boolean cancelled = false;

        BlockBreakEvent(String block) {
            this.block = block;
        }

        @Override
        public boolean isCancelled() { return cancelled; }

        @Override
        public void setCancelled(boolean v) { cancelled = v; }
    }

    static class DamageEvent implements Event, Modifiable<Double> {
        private Double value;

        DamageEvent(double value) { this.value = value; }

        @Override
        public Double getValue() { return value; }

        @Override
        public void setValue(Double v) { value = v; }
    }

    // Annotation-based subscriber

    static class GameListener {
        @Subscribe(priority = Priority.HIGH)
        public void onJump(PlayerJumpEvent e) {
            IO.println(e.player + " jumped!");
        }

        @Subscribe
        public void onBreak(BlockBreakEvent e) {
            e.cancel();
        }
    }

    // Basic usage: annotation subscribe, lambda subscribe, unsubscribe

    static void basicUsage() {
        EventBus bus = EventBus.create();

        GameListener listener = new GameListener();
        bus.subscribe(listener);
        bus.post(new PlayerJumpEvent("Steve"));

        bus.unsubscribe(listener);
        bus.post(new PlayerJumpEvent("Alex")); // no output

        Subscription sub = bus.subscribe(
                PlayerJumpEvent.class,
                Priority.NORMAL,
                e -> IO.println("Lambda: " + e.player)
        );
        bus.post(new PlayerJumpEvent("Steve"));
        sub.cancel();
    }

    // Subscription as AutoCloseable (try-with-resources)

    static void tryWithResources() {
        EventBus bus = EventBus.create();
        try (Subscription sub = bus.subscribe(
                PlayerJumpEvent.class, Priority.NORMAL,
                e -> IO.println("scoped: " + e.player))) {
            bus.post(new PlayerJumpEvent("Steve")); // fires
        }
        bus.post(new PlayerJumpEvent("Alex")); // does not fire; sub was closed
    }

    // Cancellable: dispatch stops as soon as a handler cancels the event

    static void cancellable() {
        EventBus bus = EventBus.create();
        bus.subscribe(BlockBreakEvent.class, Priority.HIGH, e -> e.cancel());
        bus.subscribe(BlockBreakEvent.class, Priority.LOW,
                e -> IO.println("Should not reach here"));

        BlockBreakEvent result = bus.post(new BlockBreakEvent("stone"));
        IO.println("Cancelled: " + result.isCancelled()); // true
    }

    // Modifiable: handlers can read and replace the event's value

    static void modifiable() {
        EventBus bus = EventBus.create();
        bus.subscribe(DamageEvent.class, Priority.NORMAL, e -> e.setValue(e.getValue() * 0.5));

        DamageEvent result = bus.post(new DamageEvent(10.0));
        IO.println("Damage after halving: " + result.getValue()); // 5.0
    }

    // Custom error handler: exceptions from handlers are routed here, not re-thrown

    static void customErrorHandler() {
        EventBus bus = EventBus.createWithHandler(
                t -> IO.println("Caught: " + t.getMessage())
        );
        bus.subscribe(PlayerJumpEvent.class, Priority.NORMAL, e -> {
            throw new RuntimeException("handler blew up");
        });
        bus.post(new PlayerJumpEvent("Steve"));
    }

    public static void main(String[] args) {
        IO.println("=== Basic usage ===");
        basicUsage();
        IO.println("\n=== Try-with-resources ===");
        tryWithResources();
        IO.println("\n=== Cancellable ===");
        cancellable();
        IO.println("\n=== Modifiable ===");
        modifiable();
        IO.println("\n=== Custom error handler ===");
        customErrorHandler();
    }
}

