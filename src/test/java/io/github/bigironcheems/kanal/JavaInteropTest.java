package io.github.bigironcheems.kanal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the Java-facing public API surface.
 * <p>
 * Each test exercises a pattern documented for Java callers:
 * EventBus.create(), createWithHandler(), Consumer-based subscribe/subscribeAll,
 * TypedEventBusFactory.typed(), @Subscribe on a Java class, Cancellable,
 * Modifiable, Subscription as AutoCloseable (try-with-resources).
 * <p>
 * Consumer-based overloads are used throughout; no Function1/Unit boilerplate needed.
 */
class JavaInteropTest {

    // Event fixtures

    static class SimpleEvent implements Event {
    }

    static class CancellableEvent implements Event, Cancellable {
        private boolean cancelled = false;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean v) {
            cancelled = v;
        }
    }

    static class ModifiableEvent implements Event, Modifiable<String> {
        private String value;

        ModifiableEvent(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }

        @Override
        public void setValue(String v) {
            value = v;
        }
    }

    // Annotation-based Java subscriber

    static class JavaListener {
        final List<String> received = new ArrayList<>();

        @Subscribe
        public void onSimple(SimpleEvent e) {
            received.add("simple");
        }

        @Subscribe(priority = Priority.HIGH)
        public void onCancellable(CancellableEvent e) {
            received.add("cancellable");
        }
    }

    static class JavaStaticListener {
        static final List<String> received = new ArrayList<>();

        @Subscribe
        @SuppressWarnings("unused")
        public static void onSimple(SimpleEvent e) {
            received.add("static");
        }
    }

    // Tests

    @Test
    void eventBusCreate_returnsWorkingBus() {
        EventBus bus = EventBus.create();
        assertNotNull(bus);
        assertNotNull(bus.post(new SimpleEvent()));
    }

    @Test
    void eventBusCreateWithHandler_invokesHandlerOnException() {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        EventBus bus = EventBus.createWithHandler(caught::set);

        bus.subscribe(SimpleEvent.class, Priority.NORMAL, e -> {
            throw new RuntimeException("boom");
        });
        bus.post(new SimpleEvent());

        assertNotNull(caught.get());
        assertEquals("boom", caught.get().getMessage());
    }

    @Test
    void annotationSubscriber_firesOnPost() {
        EventBus bus = EventBus.create();
        JavaListener listener = new JavaListener();
        bus.subscribe(listener);
        bus.post(new SimpleEvent());
        assertEquals(List.of("simple"), listener.received);
    }

    @Test
    void annotationSubscriber_unsubscribeStopsDispatch() {
        EventBus bus = EventBus.create();
        JavaListener listener = new JavaListener();
        bus.subscribe(listener);
        bus.post(new SimpleEvent());
        bus.unsubscribe(listener);
        bus.post(new SimpleEvent());
        assertEquals(1, listener.received.size());
    }

    @Test
    void staticSubscriber_firesViaSubscribeStatic() {
        JavaStaticListener.received.clear();
        EventBus bus = EventBus.create();
        bus.subscribeStatic(JavaStaticListener.class);
        bus.post(new SimpleEvent());
        bus.unsubscribeStatic(JavaStaticListener.class);
        bus.post(new SimpleEvent());
        assertEquals(List.of("static"), JavaStaticListener.received);
    }

    @Test
    void lambdaSubscribe_firesAndCancelRemovesHandler() {
        EventBus bus = EventBus.create();
        AtomicInteger count = new AtomicInteger();
        Subscription sub = bus.subscribe(SimpleEvent.class, Priority.NORMAL, e -> count.incrementAndGet());
        bus.post(new SimpleEvent());
        sub.cancel();
        bus.post(new SimpleEvent());
        assertEquals(1, count.get());
    }

    @Test
    void lambdaSubscribe_tryWithResources_removesHandler() throws Exception {
        EventBus bus = EventBus.create();
        AtomicInteger count = new AtomicInteger();
        try (Subscription sub = bus.subscribe(SimpleEvent.class, Priority.NORMAL, e -> count.incrementAndGet())) {
            bus.post(new SimpleEvent());
        }
        bus.post(new SimpleEvent());
        assertEquals(1, count.get());
    }

    @Test
    void subscribeAll_firesForEveryEvent() {
        EventBus bus = EventBus.create();
        AtomicInteger count = new AtomicInteger();
        bus.subscribeAll(Priority.NORMAL, e -> count.incrementAndGet());
        bus.post(new SimpleEvent());
        bus.post(new CancellableEvent());
        assertEquals(2, count.get());
    }

    @Test
    void cancellable_cancelStopsDispatch() {
        EventBus bus = EventBus.create();
        AtomicBoolean secondFired = new AtomicBoolean(false);
        bus.subscribe(CancellableEvent.class, Priority.HIGH, e -> e.cancel());
        bus.subscribe(CancellableEvent.class, Priority.LOW, e -> secondFired.set(true));
        CancellableEvent result = bus.post(new CancellableEvent());
        assertTrue(result.isCancelled());
        assertFalse(secondFired.get());
    }

    @Test
    void modifiable_handlersCanReplaceValue() {
        EventBus bus = EventBus.create();
        bus.subscribe(ModifiableEvent.class, Priority.NORMAL, e -> e.setValue(e.getValue().toUpperCase()));
        ModifiableEvent result = bus.post(new ModifiableEvent("hello"));
        assertEquals("HELLO", result.getValue());
    }

    @Test
    void isListening_reflectsRegistrationState() {
        EventBus bus = EventBus.create();
        assertFalse(bus.isListening(SimpleEvent.class));
        Subscription sub = bus.subscribe(SimpleEvent.class, Priority.NORMAL, e -> {
        });
        assertTrue(bus.isListening(SimpleEvent.class));
        sub.cancel();
        assertFalse(bus.isListening(SimpleEvent.class));
    }

    @Test
    void isListeningAll_reflectsWildcardState() {
        EventBus bus = EventBus.create();
        assertFalse(bus.isListeningAll());
        Subscription sub = bus.subscribeAll(Priority.NORMAL, e -> {
        });
        assertTrue(bus.isListeningAll());
        sub.cancel();
        assertFalse(bus.isListeningAll());
    }

    @Test
    void typedEventBusFactory_typed_restrictsDomain() {
        EventBus underlying = EventBus.create();
        TypedEventBus<SimpleEvent> typed = TypedEventBusFactory.typed(underlying, SimpleEvent.class);
        assertNotNull(typed);
        assertSame(underlying, typed.getDelegate());
        AtomicBoolean fired = new AtomicBoolean(false);
        typed.subscribe(SimpleEvent.class, Priority.NORMAL, e -> fired.set(true));
        typed.post(new SimpleEvent());
        assertTrue(fired.get());
    }

    @Test
    void typedEventBus_subscribeAll_firesForAllEvents() {
        EventBus underlying = EventBus.create();
        TypedEventBus<SimpleEvent> typed = TypedEventBusFactory.typed(underlying, SimpleEvent.class);
        AtomicInteger count = new AtomicInteger();
        typed.subscribeAll(Priority.NORMAL, e -> count.incrementAndGet());
        typed.post(new SimpleEvent());
        typed.post(new SimpleEvent());
        assertEquals(2, count.get());
    }

    @Test
    void typedEventBus_unsubscribe_removesAnnotationHandler() {
        EventBus underlying = EventBus.create();
        TypedEventBus<SimpleEvent> typed = TypedEventBusFactory.typed(underlying, SimpleEvent.class);
        JavaListener listener = new JavaListener();
        typed.subscribe(listener);
        typed.post(new SimpleEvent());
        typed.unsubscribe(listener);
        typed.post(new SimpleEvent());
        assertEquals(1, listener.received.size());
    }

    @Test
    void priorityOrdering_highBeforeLow() {
        EventBus bus = EventBus.create();
        List<String> order = new ArrayList<>();
        bus.subscribe(SimpleEvent.class, Priority.LOW, e -> order.add("low"));
        bus.subscribe(SimpleEvent.class, Priority.HIGH, e -> order.add("high"));
        bus.subscribe(SimpleEvent.class, Priority.NORMAL, e -> order.add("normal"));
        bus.post(new SimpleEvent());
        assertEquals(List.of("high", "normal", "low"), order);
    }
}
