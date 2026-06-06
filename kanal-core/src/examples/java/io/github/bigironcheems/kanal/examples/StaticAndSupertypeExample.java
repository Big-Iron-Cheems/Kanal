package io.github.bigironcheems.kanal.examples;

import io.github.bigironcheems.kanal.*;

/**
 * Demonstrates static subscribers and supertype dispatch from Java.
 * <p>
 * Static methods annotated with {@code @Subscribe} are registered via
 * {@code subscribeStatic} / {@code unsubscribeStatic} using the class token.
 * Supertype dispatch fires handlers registered for any superclass or interface
 * in the posted event's hierarchy.
 */
public class StaticAndSupertypeExample {

    // Event definitions

    static class PlayerJumpEvent implements Event {
        final String player;

        PlayerJumpEvent(String player) {
            this.player = player;
        }
    }

    static abstract class BaseAttackEvent implements Event {
        final double damage;

        BaseAttackEvent(double damage) {
            this.damage = damage;
        }
    }

    static class CriticalHitEvent extends BaseAttackEvent {
        final double multiplier;

        CriticalHitEvent(double damage, double multiplier) {
            super(damage);
            this.multiplier = multiplier;
        }
    }

    // Static subscriber

    static class ServerListener {
        @Subscribe
        public static void onJump(PlayerJumpEvent e) {
            IO.println("[Server] " + e.player + " jumped");
        }
    }

    static void staticSubscriber() {
        EventBus bus = EventBus.create();

        bus.subscribeStatic(ServerListener.class);
        IO.println("After subscribeStatic:");
        bus.post(new PlayerJumpEvent("Steve"));

        bus.unsubscribeStatic(ServerListener.class);
        IO.println("After unsubscribeStatic:");
        bus.post(new PlayerJumpEvent("Alex")); // no output
        IO.println("(no output above; static handler removed)");
    }

    // Supertype dispatch: posting CriticalHitEvent also reaches BaseAttackEvent handlers

    static void supertypeDispatch() {
        EventBus bus = EventBus.create();

        bus.subscribe(BaseAttackEvent.class, Priority.NORMAL,
                e -> IO.println("Attack for " + e.damage));
        bus.subscribe(CriticalHitEvent.class, Priority.NORMAL,
                e -> IO.println("Critical x" + e.multiplier + "!"));

        bus.post(new CriticalHitEvent(10.0, 2.0));
        // Output:
        //   Critical x2.0!   (CriticalHitEvent handler, higher specificity via priority sort)
        //   Attack for 10.0  (BaseAttackEvent handler)
    }

    public static void main(String[] args) {
        IO.println("=== Static subscriber ===");
        staticSubscriber();
        IO.println("\n=== Supertype dispatch ===");
        supertypeDispatch();
    }
}

