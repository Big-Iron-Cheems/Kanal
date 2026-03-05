package io.github.bigironcheems.kanal.examples;

import io.github.bigironcheems.kanal.*;

/**
 * Demonstrates {@link TypedEventBus} from Java.
 * <p>
 * A typed bus restricts the domain to a specific event hierarchy, providing
 * compile-time safety that only compatible event types are posted or subscribed.
 * It delegates to an underlying {@link EventBus} for dispatch.
 */
public class TypedBusExample {

    // Event hierarchy

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

    // Typed bus: subscribe, post, subscribeAll

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
    }

    // Wildcard on a typed bus: fires for every event in the domain

    static void typedBusWildcard() {
        EventBus underlying = EventBus.create();
        TypedEventBus<NetworkEvent> networkBus =
                TypedEventBusFactory.typed(underlying, NetworkEvent.class);

        networkBus.subscribe(PacketReceived.class, Priority.NORMAL,
                e -> IO.println("Received " + e.bytes.length + " bytes"));

        networkBus.subscribeAll(Priority.LOWEST,
                e -> IO.println("[log] " + e.getClass().getSimpleName()));

        networkBus.post(new PacketReceived(new byte[0]));
        // Output:
        //   Received 0 bytes
        //   [log] PacketReceived
    }

    // getDelegate: access the underlying bus if needed

    static void typedBusDelegate() {
        EventBus underlying = EventBus.create();
        TypedEventBus<NetworkEvent> networkBus =
                TypedEventBusFactory.typed(underlying, NetworkEvent.class);

        IO.println("Same underlying bus: " + (networkBus.getDelegate() == underlying)); // true
    }

    public static void main(String[] args) {
        IO.println("=== Typed bus ===");
        typedBus();
        IO.println("\n=== Typed bus wildcard ===");
        typedBusWildcard();
        IO.println("\n=== Typed bus delegate ===");
        typedBusDelegate();
    }
}

