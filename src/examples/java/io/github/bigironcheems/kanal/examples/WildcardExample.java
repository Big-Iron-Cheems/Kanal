package io.github.bigironcheems.kanal.examples;

import io.github.bigironcheems.kanal.*;

/**
 * Demonstrates wildcard listeners from Java.
 * <p>
 * {@code subscribeAll} fires for every event posted to the bus, interleaved with
 * typed handlers by priority. A wildcard at {@code Priority.HIGHEST} runs before
 * all typed handlers at {@code Priority.NORMAL}; {@code Priority.LOWEST} runs after.
 */
public class WildcardExample {

    static class PlayerJumpEvent implements Event {
        final String player;

        PlayerJumpEvent(String player) {
            this.player = player;
        }
    }

    static class BlockBreakEvent implements Event {
        final String block;

        BlockBreakEvent(String block) {
            this.block = block;
        }
    }

    // Wildcard handlers interleaved with typed handlers by priority

    static void wildcardUsage() {
        EventBus bus = EventBus.create();

        // Audit hook; runs before any typed handler
        Subscription audit = bus.subscribeAll(Priority.HIGHEST,
                e -> IO.println("[AUDIT] dispatching " + e.getClass().getSimpleName()));

        // Logging; runs after all domain handlers
        Subscription log = bus.subscribeAll(Priority.LOWEST,
                e -> IO.println("[LOG] dispatched " + e.getClass().getSimpleName()));

        bus.subscribe(PlayerJumpEvent.class, Priority.NORMAL,
                e -> IO.println(e.player + " jumped"));

        bus.post(new PlayerJumpEvent("Steve"));
        // Output order: [AUDIT], jumped, [LOG]

        audit.cancel();
        log.cancel();
    }

    // isListeningAll reflects wildcard registration state

    static void wildcardQuery() {
        EventBus bus = EventBus.create();
        IO.println(bus.isListeningAll()); // false

        Subscription sub = bus.subscribeAll(Priority.NORMAL, e -> {});
        IO.println(bus.isListeningAll()); // true

        sub.cancel();
        IO.println(bus.isListeningAll()); // false
    }

    // Wildcard fires for every event type

    static void wildcardMultipleTypes() {
        EventBus bus = EventBus.create();
        bus.subscribeAll(Priority.NORMAL,
                e -> IO.println("wildcard: " + e.getClass().getSimpleName()));

        bus.post(new PlayerJumpEvent("Steve"));
        bus.post(new BlockBreakEvent("stone"));
    }

    public static void main(String[] args) {
        IO.println("=== Wildcard usage ===");
        wildcardUsage();
        IO.println("\n=== isListeningAll query ===");
        wildcardQuery();
        IO.println("\n=== Wildcard multiple types ===");
        wildcardMultipleTypes();
    }
}

