package io.github.bigironcheems.kanal.examples;

import io.github.bigironcheems.kanal.*;

/**
 * Demonstrates the Java-facing public API surface.
 * <p>
 * Consumer-based subscribe/subscribeAll overloads are used throughout;
 * no Function1/Unit boilerplate is needed.
 */
public class JavaUsageExample {

    // ── Event definitions ─────────────────────────────────────────────────────

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
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean v) {
            cancelled = v;
        }
    }

    static class DamageEvent implements Event, Modifiable<Double> {
        private Double value;

        DamageEvent(double value) {
            this.value = value;
        }

        @Override
        public Double getValue() {
            return value;
        }

        @Override
        public void setValue(Double v) {
            value = v;
        }
    }

    // ── Annotation-based subscriber ───────────────────────────────────────────

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

    // ── Static subscriber ─────────────────────────────────────────────────────

    static class ServerListener {
        @Subscribe
        public static void onJump(PlayerJumpEvent e) {
            IO.println("[Server] " + e.player + " jumped");
        }
    }

    // ── Basic usage ───────────────────────────────────────────────────────────

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

    // ── Subscription as AutoCloseable (try-with-resources) ────────────────────

    static void tryWithResources() {
        EventBus bus = EventBus.create();
        try (Subscription sub = bus.subscribe(
            PlayerJumpEvent.class, Priority.NORMAL,
            e -> IO.println("scoped: " + e.player))) {
            bus.post(new PlayerJumpEvent("Steve")); // fires
        }
        bus.post(new PlayerJumpEvent("Alex")); // does not fire; sub was closed
    }

    // ── Static subscriber ─────────────────────────────────────────────────────

    static void staticSubscriber() {
        EventBus bus = EventBus.create();
        bus.subscribeStatic(ServerListener.class);
        bus.post(new PlayerJumpEvent("Steve"));
        bus.unsubscribeStatic(ServerListener.class);
        bus.post(new PlayerJumpEvent("Alex")); // no output
    }

    // ── Wildcard listener ─────────────────────────────────────────────────────

    static void wildcardListener() {
        EventBus bus = EventBus.create();
        bus.subscribeAll(Priority.NORMAL, e -> IO.println("wildcard: " + e.getClass().getSimpleName()));
        bus.post(new PlayerJumpEvent("Steve"));
        bus.post(new BlockBreakEvent("stone"));
    }

    // ── Cancellable ───────────────────────────────────────────────────────────

    static void cancellable() {
        EventBus bus = EventBus.create();
        bus.subscribe(BlockBreakEvent.class, Priority.HIGH, e -> e.cancel());

        BlockBreakEvent result = bus.post(new BlockBreakEvent("stone"));
        IO.println("Cancelled: " + result.isCancelled()); // true
    }

    // ── Modifiable ────────────────────────────────────────────────────────────

    static void modifiable() {
        EventBus bus = EventBus.create();
        bus.subscribe(DamageEvent.class, Priority.NORMAL, e -> e.setValue(e.getValue() * 0.5));

        DamageEvent result = bus.post(new DamageEvent(10.0));
        IO.println("Damage after halving: " + result.getValue()); // 5.0
    }

    // ── Custom error handler ──────────────────────────────────────────────────

    static void customErrorHandler() {
        EventBus bus = EventBus.createWithHandler(
            t -> IO.println("Caught: " + t.getMessage())
        );
        bus.subscribe(PlayerJumpEvent.class, Priority.NORMAL, e -> {
            throw new RuntimeException("handler blew up");
        });
        bus.post(new PlayerJumpEvent("Steve"));
    }

    // ── Typed bus ─────────────────────────────────────────────────────────────

    interface NetworkEvent extends Event {
    }

    static class PacketReceived implements NetworkEvent {
        final byte[] bytes;

        PacketReceived(byte[] bytes) {
            this.bytes = bytes;
        }
    }

    static class ConnectionLost implements NetworkEvent {
        final String reason;

        ConnectionLost(String reason) {
            this.reason = reason;
        }
    }

    static void typedBus() {
        EventBus underlying = EventBus.create();
        TypedEventBus<NetworkEvent> networkBus =
            TypedEventBusFactory.typed(underlying, NetworkEvent.class);

        networkBus.subscribe(PacketReceived.class, Priority.NORMAL,
            e -> IO.println("Received " + e.bytes.length + " bytes"));
        networkBus.subscribe(ConnectionLost.class, Priority.NORMAL,
            e -> IO.println("Lost: " + e.reason));

        networkBus.post(new PacketReceived(new byte[]{1, 2, 3}));
        networkBus.post(new ConnectionLost("timeout"));

        networkBus.subscribeAll(Priority.LOWEST,
            e -> IO.println("[log] " + e.getClass().getSimpleName()));
        networkBus.post(new PacketReceived(new byte[0]));
    }

    // ── main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        IO.println("=== Basic usage ===");
        basicUsage();
        IO.println("\n=== Try-with-resources ===");
        tryWithResources();
        IO.println("\n=== Static subscriber ===");
        staticSubscriber();
        IO.println("\n=== Wildcard ===");
        wildcardListener();
        IO.println("\n=== Cancellable ===");
        cancellable();
        IO.println("\n=== Modifiable ===");
        modifiable();
        IO.println("\n=== Custom error handler ===");
        customErrorHandler();
        IO.println("\n=== Typed bus ===");
        typedBus();
    }
}
