package io.github.bigironcheems.kanal

import kotlin.test.*

class EventBusTest {

    // Shared fixture events

    class SimpleEvent : Event
    class OtherEvent : Event
    class CancellableEvent : Event, Cancellable {
        override var isCancelled = false
    }

    // 1. Basic dispatch

    @Test
    fun `posted event is received by a subscribed handler`() {
        val bus = EventBus()
        var received = false

        val listener = object {
            @Subscribe
            fun on(e: SimpleEvent) {
                received = true
            }
        }

        bus.subscribe(listener)
        bus.post(SimpleEvent())

        assertTrue(received)
    }

    @Test
    fun `post returns the same event instance`() {
        val bus = EventBus()
        bus.subscribe(object {
            @Subscribe
            fun on(e: SimpleEvent) {
            }
        })
        val event = SimpleEvent()
        assertSame(event, bus.post(event))
    }

    @Test
    fun `handler is not called for a different event type`() {
        val bus = EventBus()
        var called = false

        bus.subscribe(object {
            @Subscribe
            fun on(e: OtherEvent) {
                called = true
            }
        })

        bus.post(SimpleEvent())
        assertFalse(called)
    }

    // 2. Subscribe / unsubscribe lifecycle

    @Test
    fun `unsubscribed handler is no longer called`() {
        val bus = EventBus()
        var count = 0

        val listener = object {
            @Subscribe
            fun on(e: SimpleEvent) {
                count++
            }
        }

        bus.subscribe(listener)
        bus.post(SimpleEvent())   // count = 1
        bus.unsubscribe(listener)
        bus.post(SimpleEvent())   // should not increment

        assertEquals(1, count)
    }

    @Test
    fun `subscribing the same object twice is idempotent`() {
        val bus = EventBus()
        var count = 0

        val listener = object {
            @Subscribe
            fun on(e: SimpleEvent) {
                count++
            }
        }

        bus.subscribe(listener)
        bus.subscribe(listener)
        bus.post(SimpleEvent())

        // Second subscribe is a no-op; the handler fires exactly once.
        assertEquals(1, count)
    }

    //  3. Priority ordering

    @Test
    fun `handlers fire in descending priority order`() {
        val bus = EventBus()
        val order = mutableListOf<String>()

        bus.subscribe(object {
            @Subscribe(priority = Priority.LOW)
            fun low(e: SimpleEvent) {
                order += "LOW"
            }

            @Subscribe(priority = Priority.HIGH)
            fun high(e: SimpleEvent) {
                order += "HIGH"
            }

            @Subscribe(priority = Priority.NORMAL)
            fun normal(e: SimpleEvent) {
                order += "NORMAL"
            }
        })

        bus.post(SimpleEvent())
        assertEquals(listOf("HIGH", "NORMAL", "LOW"), order)
    }

    @Test
    fun `handlers with equal priority fire in subscription order`() {
        val bus = EventBus()
        val order = mutableListOf<Int>()

        bus.subscribe(object {
            @Subscribe
            fun on(e: SimpleEvent) {
                order += 1
            }
        })
        bus.subscribe(object {
            @Subscribe
            fun on(e: SimpleEvent) {
                order += 2
            }
        })
        bus.subscribe(object {
            @Subscribe
            fun on(e: SimpleEvent) {
                order += 3
            }
        })

        bus.post(SimpleEvent())
        assertEquals(listOf(1, 2, 3), order)
    }

    // 4. Cancellation

    @Test
    fun `dispatch stops after a handler cancels the event`() {
        val bus = EventBus()
        val called = mutableListOf<String>()

        bus.subscribe(object {
            @Subscribe(priority = Priority.HIGH)
            fun first(e: CancellableEvent) {
                called += "first"
                e.cancel()
            }

            @Subscribe(priority = Priority.NORMAL)
            fun second(e: CancellableEvent) {
                called += "second"   // must NOT run
            }
        })

        bus.post(CancellableEvent())
        assertEquals(listOf("first"), called)
    }

    @Test
    fun `non-cancelled event reaches all handlers`() {
        val bus = EventBus()
        var count = 0

        val listener = object {
            @Subscribe(priority = Priority.HIGH)
            fun a(e: CancellableEvent) {
                count++
            }

            @Subscribe(priority = Priority.NORMAL)
            fun b(e: CancellableEvent) {
                count++
            }

            @Subscribe(priority = Priority.LOW)
            fun c(e: CancellableEvent) {
                count++
            }
        }

        bus.subscribe(listener)
        bus.post(CancellableEvent())
        assertEquals(3, count)
    }

    // 5. Static / companion-object handlers

    object StaticListener {
        var fired = false

        @JvmStatic
        @Subscribe
        fun on(e: SimpleEvent) {
            fired = true
        }
    }

    @Test
    fun `static handler is called via subscribeStatic`() {
        StaticListener.fired = false
        val bus = EventBus()
        bus.subscribeStatic(StaticListener::class.java)
        bus.post(SimpleEvent())
        assertTrue(StaticListener.fired)
    }

    @Test
    fun `unsubscribeStatic removes static handler`() {
        StaticListener.fired = false
        val bus = EventBus()
        bus.subscribeStatic(StaticListener::class.java)
        bus.unsubscribeStatic(StaticListener::class.java)
        bus.post(SimpleEvent())
        assertFalse(StaticListener.fired)
    }

    // 6. Multiple event types on one bus

    @Test
    fun `bus routes each event type independently`() {
        val bus = EventBus()
        var simpleCount = 0
        var otherCount = 0

        bus.subscribe(object {
            @Subscribe
            fun onSimple(e: SimpleEvent) {
                simpleCount++
            }

            @Subscribe
            fun onOther(e: OtherEvent) {
                otherCount++
            }
        })

        bus.post(SimpleEvent())
        bus.post(SimpleEvent())
        bus.post(OtherEvent())

        assertEquals(2, simpleCount)
        assertEquals(1, otherCount)
    }

    // 7. isListening

    @Test
    fun `isListening returns false when no handlers are registered`() {
        assertFalse(EventBus().isListening(SimpleEvent::class.java))
    }

    @Test
    fun `isListening returns true after subscribing`() {
        val bus = EventBus()
        bus.subscribe(object {
            @Subscribe
            fun on(e: SimpleEvent) {
            }
        })
        assertTrue(bus.isListening(SimpleEvent::class.java))
    }

    // 8. Exception handling

    @Test
    fun `exception in handler is forwarded to exceptionHandler and dispatch continues`() {
        val errors = mutableListOf<Throwable>()
        val bus = EventBus { errors += it }

        var secondCalled = false
        bus.subscribe(object {
            @Subscribe(priority = Priority.HIGH)
            fun bad(e: SimpleEvent) {
                throw RuntimeException("boom")
            }

            @Subscribe(priority = Priority.NORMAL)
            fun good(e: SimpleEvent) {
                secondCalled = true
            }
        })

        bus.post(SimpleEvent())

        assertEquals(1, errors.size)
        assertIs<RuntimeException>(errors[0])
        assertTrue(secondCalled, "Dispatch should continue after a handler exception")
    }

    // 9. Supertype dispatch

    open class BaseEvent : Event
    class ConcreteEvent : BaseEvent()
    interface MarkerEvent : Event

    @Test
    fun `handler on superclass receives subclass event`() {
        val bus = EventBus()
        var received = false
        bus.subscribe(object {
            @Subscribe
            fun on(e: BaseEvent) {
                received = true
            }
        })
        bus.post(ConcreteEvent())
        assertTrue(received, "SuperEvent handler should fire when SubEvent is posted")
    }

    @Test
    fun `handler on exact type and handler on supertype both fire`() {
        val bus = EventBus()
        val fired = mutableListOf<String>()
        bus.subscribe(object {
            @Subscribe
            fun onBase(e: BaseEvent) {
                fired += "base"
            }

            @Subscribe
            fun onConcrete(e: ConcreteEvent) {
                fired += "concrete"
            }
        })
        bus.post(ConcreteEvent())
        assertTrue("base" in fired)
        assertTrue("concrete" in fired)
    }

    @Test
    fun `handler on interface receives implementing event`() {
        val bus = EventBus()
        var received = false

        class IfaceEvent : Event, MarkerEvent
        bus.subscribe(object {
            @Subscribe
            fun on(e: MarkerEvent) {
                received = true
            }
        })
        bus.post(IfaceEvent())
        assertTrue(received)
    }

    @Test
    fun `supertype handler does not fire for unrelated event type`() {
        val bus = EventBus()
        var received = false
        bus.subscribe(object {
            @Subscribe
            fun on(e: BaseEvent) {
                received = true
            }
        })
        bus.post(SimpleEvent())   // SimpleEvent does not extend BaseEvent
        assertFalse(received)
    }

    @Test
    fun `priority is respected across supertype and concrete handlers`() {
        val bus = EventBus()
        val order = mutableListOf<String>()
        bus.subscribe(object {
            @Subscribe(priority = Priority.LOW)
            fun onBase(e: BaseEvent) {
                order += "base-LOW"
            }

            @Subscribe(priority = Priority.HIGH)
            fun onConcrete(e: ConcreteEvent) {
                order += "concrete-HIGH"
            }
        })
        bus.post(ConcreteEvent())
        assertEquals(listOf("concrete-HIGH", "base-LOW"), order)
    }

    @Test
    fun `dispatch cache is invalidated after unsubscribe`() {
        val bus = EventBus()
        var count = 0
        val listener = object {
            @Subscribe
            fun on(e: SimpleEvent) {
                count++
            }
        }
        bus.subscribe(listener)
        bus.post(SimpleEvent())   // warms dispatch cache
        bus.unsubscribe(listener)
        bus.post(SimpleEvent())   // must NOT increment
        assertEquals(1, count)
    }

    // 10. Lambda subscribe

    @Test
    fun `lambda subscribe fires on matching event`() {
        val bus = EventBus()
        var received = false
        bus.subscribe<SimpleEvent> { received = true }
        bus.post(SimpleEvent())
        assertTrue(received)
    }

    @Test
    fun `lambda subscribe respects priority versus annotation handler`() {
        val bus = EventBus()
        val order = mutableListOf<String>()
        bus.subscribe<SimpleEvent>(Priority.LOW) { order += "lambda-LOW" }
        bus.subscribe(object {
            @Subscribe(priority = Priority.HIGH)
            fun on(e: SimpleEvent) {
                order += "annotation-HIGH"
            }
        })
        bus.post(SimpleEvent())
        assertEquals(listOf("annotation-HIGH", "lambda-LOW"), order)
    }

    @Test
    fun `lambda subscribe does not fire for different event type`() {
        val bus = EventBus()
        var fired = false
        bus.subscribe<OtherEvent> { fired = true }
        bus.post(SimpleEvent())
        assertFalse(fired)
    }

    @Test
    fun `isListening reified returns true after lambda subscribe`() {
        val bus = EventBus()
        assertFalse(bus.isListening<SimpleEvent>())
        bus.subscribe<SimpleEvent> { }
        assertTrue(bus.isListening<SimpleEvent>())
    }

    // 11. Modifiable events

    class DamageEvent(override var value: Double) : Event, Modifiable<Double>
    class MessageEvent(override var value: String) : Event, Modifiable<String>
    class CancellableModEvent(override var value: Int, override var isCancelled: Boolean = false) : Event,
        Modifiable<Int>, Cancellable

    @Test
    fun `handler can read and replace modifiable value`() {
        val bus = EventBus()
        bus.subscribe<DamageEvent> { e -> e.value *= 0.5 }

        val result = bus.post(DamageEvent(10.0)).value
        assertEquals(5.0, result)
    }

    @Test
    fun `multiple handlers each see the updated value from the previous handler`() {
        val bus = EventBus()
        // HIGH runs first: 10 -> 8; NORMAL runs second: 8 -> 6
        bus.subscribe<DamageEvent>(Priority.HIGH) { e -> e.value -= 2.0 }
        bus.subscribe<DamageEvent>(Priority.NORMAL) { e -> e.value -= 2.0 }

        val result = bus.post(DamageEvent(10.0)).value
        assertEquals(6.0, result)
    }

    @Test
    fun `modifiable value is not changed when no handlers are registered`() {
        val bus = EventBus()
        val result = bus.post(DamageEvent(10.0)).value
        assertEquals(10.0, result)
    }

    @Test
    fun `modifiable works with string values`() {
        val bus = EventBus()
        bus.subscribe<MessageEvent>(Priority.HIGH) { e -> e.value = e.value.uppercase() }
        bus.subscribe<MessageEvent>(Priority.NORMAL) { e -> e.value = "[${e.value}]" }

        val result = bus.post(MessageEvent("hello")).value
        assertEquals("[HELLO]", result)
    }

    @Test
    fun `cancellation stops modifiable dispatch mid-chain`() {
        val bus = EventBus()
        bus.subscribe<CancellableModEvent>(Priority.HIGH) { e ->
            e.value += 1
            e.cancel()
        }
        bus.subscribe<CancellableModEvent>(Priority.NORMAL) { e ->
            e.value += 100   // must NOT run
        }

        val event = bus.post(CancellableModEvent(0))
        assertEquals(1, event.value)
        assertTrue(event.isCancelled)
    }

    @Test
    fun `modifiable is composable with supertype dispatch`() {
        val bus = EventBus()

        // Handler on the base Modifiable interface itself; should NOT be found
        // since Event+Modifiable is not the same as DamageEvent.
        // Instead, register on DamageEvent and confirm supertype handler fires.
        open class BaseDamageEvent(override var value: Double) : Event, Modifiable<Double>
        class CritEvent(value: Double) : BaseDamageEvent(value)

        bus.subscribe(object {
            @Subscribe
            fun on(e: BaseDamageEvent) {
                e.value *= 2.0
            }
        })

        val result = bus.post(CritEvent(5.0)).value
        assertEquals(10.0, result)
    }

    // 12. Subscription token + unsubscribeAll

    @Test
    fun `subscription cancel stops handler from firing`() {
        val bus = EventBus()
        var count = 0
        val sub = bus.subscribe<SimpleEvent> { count++ }
        bus.post(SimpleEvent())
        sub.cancel()
        bus.post(SimpleEvent())
        assertEquals(1, count)
    }

    @Test
    fun `subscription cancel is idempotent`() {
        val bus = EventBus()
        var count = 0
        val sub = bus.subscribe<SimpleEvent> { count++ }
        sub.cancel()
        sub.cancel()   // must not throw
        bus.post(SimpleEvent())
        assertEquals(0, count)
    }

    @Test
    fun `subscription close is alias for cancel`() {
        val bus = EventBus()
        var count = 0
        val sub = bus.subscribe<SimpleEvent> { count++ }
        sub.close()
        bus.post(SimpleEvent())
        assertEquals(0, count)
    }

    @Test
    fun `subscription use block removes handler after block exits`() {
        val bus = EventBus()
        var count = 0
        bus.subscribe<SimpleEvent> { count++ }.use { }
        bus.post(SimpleEvent())
        assertEquals(0, count)
    }

    @Test
    fun `cancelling one subscription does not affect others`() {
        val bus = EventBus()
        var a = 0
        var b = 0
        val subA = bus.subscribe<SimpleEvent> { a++ }
        bus.subscribe<SimpleEvent> { b++ }
        subA.cancel()
        bus.post(SimpleEvent())
        assertEquals(0, a)
        assertEquals(1, b)
    }

    @Test
    fun `unsubscribeAll removes every handler`() {
        val bus = EventBus()
        var count = 0
        bus.subscribe(object {
            @Subscribe
            fun on(e: SimpleEvent) {
                count++
            }
        })
        bus.subscribe<SimpleEvent> { count++ }
        bus.subscribe<SimpleEvent> { count++ }
        bus.post(SimpleEvent())   // all three fire -> count = 3
        bus.unsubscribeAll()
        bus.post(SimpleEvent())   // none should fire
        assertEquals(3, count)
    }

    @Test
    fun `isListening returns false after unsubscribeAll`() {
        val bus = EventBus()
        bus.subscribe<SimpleEvent> { }
        bus.unsubscribeAll()
        assertFalse(bus.isListening<SimpleEvent>())
    }

    // 13. Kotlin object subscriber paths

    object MixedObject {
        var staticFired = false
        var instanceFired = false

        @JvmStatic
        @Subscribe
        fun onStatic(e: SimpleEvent) {
            staticFired = true
        }

        @Subscribe
        fun onInstance(e: OtherEvent) {
            instanceFired = true
        }
    }

    @Test
    fun `subscribeStatic finds @JvmStatic methods on a Kotlin object`() {
        MixedObject.staticFired = false
        val bus = EventBus()
        bus.subscribeStatic(MixedObject::class.java)
        bus.post(SimpleEvent())
        assertTrue(MixedObject.staticFired)
    }

    @Test
    fun `subscribe(instance) finds non-@JvmStatic methods on a Kotlin object`() {
        MixedObject.instanceFired = false
        val bus = EventBus()
        bus.subscribe(MixedObject)
        bus.post(OtherEvent())
        assertTrue(MixedObject.instanceFired)
    }

    @Test
    fun `subscribeStatic does NOT pick up non-@JvmStatic object methods`() {
        MixedObject.instanceFired = false
        val bus = EventBus()
        bus.subscribeStatic(MixedObject::class.java)
        bus.post(OtherEvent())   // onInstance is not static; must not fire
        assertFalse(MixedObject.instanceFired)
    }

    // 14. Empty-listener cache fix

    @Test
    fun `posting an unlistened event type does not pollute dispatch cache`() {
        val bus = EventBus()
        // No handlers registered at all; post must return cleanly without
        // inserting anything into the dispatch cache.
        repeat(100) { bus.post(SimpleEvent()) }
        // Subscribe after the posts; handler must still fire.
        var fired = false
        bus.subscribe<SimpleEvent> { fired = true }
        bus.post(SimpleEvent())
        assertTrue(fired)
    }

    @Test
    fun `posting different unlistened types does not grow cache unboundedly`() {
        val bus = EventBus()
        // Post several distinct types with no listeners. If the bug were present,
        // dispatchCache would accumulate an entry per type. We verify behavior stays
        // correct after subscribing to one of them later.
        bus.post(SimpleEvent())
        bus.post(OtherEvent())
        var count = 0
        bus.subscribe<SimpleEvent> { count++ }
        bus.post(SimpleEvent())
        bus.post(OtherEvent())   // still no listener; must not increment
        assertEquals(1, count)
    }

    // 15. Override deduplication

    @Test
    fun `overriding an @Subscribe method without re-annotating opts out of dispatch`() {
        val bus = EventBus()
        var count = 0

        // Base has @Subscribe; Sub overrides without annotation; opt-out.
        // The deduplication means the base's annotated version is shadowed and never seen.
        open class Base {
            @Subscribe
            open fun on(e: SimpleEvent) {
                count++
            }
        }

        class Sub : Base() {
            override fun on(e: SimpleEvent) {
                count++
            }
        }

        bus.subscribe(Sub())
        bus.post(SimpleEvent())
        assertEquals(0, count, "Unannotated override shadows the base @Subscribe; fires zero times")
    }

    @Test
    fun `overriding an @Subscribe method WITH re-annotation fires once`() {
        val bus = EventBus()
        var count = 0

        open class Base {
            @Subscribe
            open fun on(e: SimpleEvent) {
                count++
            }
        }

        class Sub : Base() {
            @Subscribe
            override fun on(e: SimpleEvent) {
                count++
            }
        }

        bus.subscribe(Sub())
        bus.post(SimpleEvent())
        assertEquals(1, count, "Re-annotated override must still fire exactly once")
    }

    @Test
    fun `non-overriding subclass handler and inherited base handler both fire`() {
        val bus = EventBus()
        val fired = mutableListOf<String>()

        // Sub has its OWN handler for OtherEvent, Base has a handler for SimpleEvent.
        // Different signatures; both should be registered.
        open class Base {
            @Subscribe
            fun onSimple(e: SimpleEvent) {
                fired += "base"
            }
        }

        class Sub : Base() {
            @Subscribe
            fun onOther(e: OtherEvent) {
                fired += "sub"
            }
        }

        bus.subscribe(Sub())
        bus.post(SimpleEvent())
        bus.post(OtherEvent())
        assertEquals(listOf("base", "sub"), fired)
    }

    // 16. Java Modifiable interop

    // Simulates what a Java class implementing Modifiable<Double> would look like
    // (Kotlin approximation; a Java interop test via a hand-rolled implementation).
    class JavaStyleDamageEvent(initialValue: Double) : Event, Modifiable<Double> {
        // Mimics Java's explicit getter/setter pattern
        private var _value: Double = initialValue
        override var value: Double
            get() = _value
            set(v) {
                _value = v
            }
    }

    @Test
    fun `explicit getter-setter Modifiable implementation works correctly`() {
        val bus = EventBus()
        bus.subscribe<JavaStyleDamageEvent> { e -> e.value *= 2.0 }
        val result = bus.post(JavaStyleDamageEvent(5.0)).value
        assertEquals(10.0, result)
    }

    @Test
    fun `multiple handlers chain through explicit getter-setter Modifiable`() {
        val bus = EventBus()
        bus.subscribe<JavaStyleDamageEvent>(Priority.HIGH) { e -> e.value += 10.0 }
        bus.subscribe<JavaStyleDamageEvent>(Priority.NORMAL) { e -> e.value *= 2.0 }
        // HIGH fires first: 5 + 10 = 15; NORMAL fires second: 15 * 2 = 30
        val result = bus.post(JavaStyleDamageEvent(5.0)).value
        assertEquals(30.0, result)
    }

    // 17. @Subscribe annotation

    @Test
    fun `@Subscribe fires on matching event`() {
        val bus = EventBus()
        var fired = false
        bus.subscribe(object {
            @Subscribe
            fun on(e: SimpleEvent) {
                fired = true
            }
        })
        bus.post(SimpleEvent())
        assertTrue(fired)
    }

    @Test
    fun `@Subscribe priority is respected`() {
        val bus = EventBus()
        val order = mutableListOf<String>()
        bus.subscribe(object {
            @Subscribe(priority = Priority.HIGH)
            fun onHigh(e: SimpleEvent) {
                order += "high"
            }

            @Subscribe(priority = Priority.LOW)
            fun onLow(e: SimpleEvent) {
                order += "low"
            }
        })
        bus.post(SimpleEvent())
        assertEquals(listOf("high", "low"), order)
    }

    @Test
    fun `@Subscribe and lower-priority @Subscribe on same bus fire in priority order`() {
        val bus = EventBus()
        val order = mutableListOf<String>()
        bus.subscribe(object {
            @Subscribe(priority = Priority.HIGH)
            fun onHigh(e: SimpleEvent) {
                order += "subscribe"
            }

            @Subscribe(priority = Priority.NORMAL)
            fun onNormal(e: SimpleEvent) {
                order += "handler"
            }
        })
        bus.post(SimpleEvent())
        assertEquals(listOf("subscribe", "handler"), order)
    }


    @Test
    fun `@Subscribe works with static methods via subscribeStatic`() {
        SubscribeStaticListener.fired = false
        val bus = EventBus()
        bus.subscribeStatic(SubscribeStaticListener::class.java)
        bus.post(SimpleEvent())
        assertTrue(SubscribeStaticListener.fired)
    }

    object SubscribeStaticListener {
        var fired = false

        @JvmStatic
        @Subscribe
        fun on(e: SimpleEvent) {
            fired = true
        }
    }

    // 18. subscribeAll / wildcard

    @Test
    fun `subscribeAll fires for every event type`() {
        val bus = EventBus()
        val received = mutableListOf<Event>()
        bus.subscribeAll { e -> received += e }

        val s = SimpleEvent()
        val o = OtherEvent()
        bus.post(s); bus.post(o)
        assertEquals(listOf(s, o), received)
    }

    @Test
    fun `subscribeAll fires even when no typed handler is registered`() {
        val bus = EventBus()
        var fired = false
        bus.subscribeAll { fired = true }
        bus.post(SimpleEvent())
        assertTrue(fired)
    }

    @Test
    fun `subscribeAll at same priority fires after typed handler (stable sort, registered later)`() {
        val bus = EventBus()
        val order = mutableListOf<String>()
        bus.subscribe(object {
            @Subscribe
            fun on(e: SimpleEvent) {
                order += "typed"
            }
        })
        bus.subscribeAll { order += "wildcard" }  // registered after; same priority, comes second
        bus.post(SimpleEvent())
        assertEquals(listOf("typed", "wildcard"), order)
    }

    @Test
    fun `subscribeAll at HIGHEST priority fires before typed handlers at NORMAL`() {
        val bus = EventBus()
        val order = mutableListOf<String>()
        bus.subscribe(object {
            @Subscribe
            fun on(e: SimpleEvent) {
                order += "typed"
            }
        })
        bus.subscribeAll(Priority.HIGHEST) { order += "wildcard-first" }
        bus.post(SimpleEvent())
        assertEquals(listOf("wildcard-first", "typed"), order)
    }

    @Test
    fun `subscribeAll respects priority among wildcard handlers`() {
        val bus = EventBus()
        val order = mutableListOf<String>()
        bus.subscribeAll(Priority.LOW) { order += "low" }
        bus.subscribeAll(Priority.HIGH) { order += "high" }
        bus.post(SimpleEvent())
        assertEquals(listOf("high", "low"), order)
    }

    @Test
    fun `subscribeAll subscription cancel removes wildcard handler`() {
        val bus = EventBus()
        var count = 0
        val sub = bus.subscribeAll { count++ }
        bus.post(SimpleEvent())
        sub.cancel()
        bus.post(SimpleEvent())
        assertEquals(1, count)
    }

    @Test
    fun `subscribeAll at LOWEST does not fire when higher-priority typed handler cancels`() {
        val bus = EventBus()
        var wildcardFired = false
        bus.subscribe<CancellableEvent>(Priority.HIGH) { e -> e.cancel() }
        bus.subscribeAll(Priority.LOWEST) { wildcardFired = true }
        bus.post(CancellableEvent())
        assertFalse(wildcardFired)
    }

    @Test
    fun `subscribeAll at HIGHEST fires before cancelling typed handler`() {
        val bus = EventBus()
        var wildcardFired = false
        bus.subscribeAll(Priority.HIGHEST) { wildcardFired = true }
        bus.subscribe<CancellableEvent>(Priority.NORMAL) { e -> e.cancel() }
        bus.post(CancellableEvent())
        assertTrue(wildcardFired)
    }

    @Test
    fun `isListeningAll returns false with no wildcard and true after subscribeAll`() {
        val bus = EventBus()
        assertFalse(bus.isListeningAll())
        bus.subscribeAll { }
        assertTrue(bus.isListeningAll())
    }

    @Test
    fun `isListeningAll returns false after cancel`() {
        val bus = EventBus()
        val sub = bus.subscribeAll { }
        sub.cancel()
        assertFalse(bus.isListeningAll())
    }

    @Test
    fun `unsubscribeAll also removes wildcard handlers`() {
        val bus = EventBus()
        var count = 0
        bus.subscribeAll { count++ }
        bus.post(SimpleEvent())
        bus.unsubscribeAll()
        bus.post(SimpleEvent())
        assertEquals(1, count)
    }

    // 19. TypedEventBus

    sealed interface DomainEvent : Event
    class DomainA : DomainEvent
    class DomainB : DomainEvent

    class OtherDomainEvent : Event  // outside DomainEvent hierarchy

    @Test
    fun `typed post dispatches to correct handler`() {
        val bus = EventBus().typed<DomainEvent>()
        var fired = false
        bus.subscribe<DomainA> { fired = true }
        bus.post(DomainA())
        assertTrue(fired)
    }

    @Test
    fun `typed post does not dispatch to unrelated handler`() {
        val underlying = EventBus()
        val bus = underlying.typed<DomainEvent>()
        var firedDomain = false
        var firedOther = false
        bus.subscribe<DomainA> { firedDomain = true }
        underlying.subscribe<OtherDomainEvent> { firedOther = true }
        bus.post(DomainA())
        assertTrue(firedDomain)
        assertFalse(firedOther)
    }

    @Test
    fun `typed bus shares registry with underlying bus`() {
        val underlying = EventBus()
        val bus = underlying.typed<DomainEvent>()
        var fired = false
        // Register on underlying, post through typed
        underlying.subscribe<DomainA> { fired = true }
        bus.post(DomainA())
        assertTrue(fired)
    }

    @Test
    fun `typed bus delegate returns the original EventBus`() {
        val underlying = EventBus()
        val bus = underlying.typed<DomainEvent>()
        assertSame(underlying, bus.delegate)
    }

    @Test
    fun `typed subscribe with @Subscribe annotation works`() {
        val bus = EventBus().typed<DomainEvent>()
        var fired = false
        bus.subscribe(object {
            @Subscribe
            fun on(e: DomainA) {
                fired = true
            }
        })
        bus.post(DomainA())
        assertTrue(fired)
    }

    @Test
    fun `typed subscribeAll fires for every domain event`() {
        val bus = EventBus().typed<DomainEvent>()
        val received = mutableListOf<Event>()
        bus.subscribeAll { e -> received += e }
        val a = DomainA()
        val b = DomainB()
        bus.post(a); bus.post(b)
        assertEquals(listOf<Event>(a, b), received)
    }

    @Test
    fun `typed isListening returns true when handler registered`() {
        val bus = EventBus().typed<DomainEvent>()
        assertFalse(bus.isListening<DomainA>())
        bus.subscribe<DomainA> { }
        assertTrue(bus.isListening<DomainA>())
    }

    @Test
    fun `typed isListeningAll delegates to underlying bus`() {
        val underlying = EventBus()
        val bus = underlying.typed<DomainEvent>()
        assertFalse(underlying.isListeningAll())
        bus.subscribeAll { }
        assertTrue(underlying.isListeningAll())
    }

    @Test
    fun `typed unsubscribe removes annotation-based handlers`() {
        val bus = EventBus().typed<DomainEvent>()
        var count = 0
        val listener = object {
            @Subscribe
            fun on(e: DomainA) {
                count++
            }
        }
        bus.subscribe(listener)
        bus.post(DomainA())
        bus.unsubscribe(listener)
        bus.post(DomainA())
        assertEquals(1, count)
    }

    @Test
    fun `typed(Class) factory produces a working bus (Java interop overload)`() {
        val underlying = EventBus()
        val bus = underlying.typed(DomainEvent::class.java)
        var fired = false
        bus.subscribe<DomainA> { fired = true }
        bus.post(DomainA())
        assertTrue(fired)
        assertSame(underlying, bus.delegate)
    }

    @Test
    fun `typed subscription cancel removes handler`() {
        val bus = EventBus().typed<DomainEvent>()
        var count = 0
        val sub = bus.subscribe<DomainA> { count++ }
        bus.post(DomainA())
        sub.cancel()
        bus.post(DomainA())
        assertEquals(1, count)
    }

    @Test
    fun `typed priority ordering is respected`() {
        val bus = EventBus().typed<DomainEvent>()
        val order = mutableListOf<String>()
        bus.subscribe<DomainA>(Priority.LOW) { order += "low" }
        bus.subscribe<DomainA>(Priority.HIGH) { order += "high" }
        bus.post(DomainA())
        assertEquals(listOf("high", "low"), order)
    }

    object TypedBusStaticHandler {
        var called = false

        @JvmStatic
        @Subscribe
        fun on(e: DomainA) {
            called = true
        }
    }

    @Test
    fun `typed subscribeStatic registers static handlers`() {
        TypedBusStaticHandler.called = false
        val bus = EventBus().typed<DomainEvent>()
        bus.subscribeStatic(TypedBusStaticHandler::class.java)
        bus.post(DomainA())
        assertTrue(TypedBusStaticHandler.called)
    }

    @Test
    fun `typed unsubscribeStatic removes static handlers`() {
        TypedBusStaticHandler.called = false
        val bus = EventBus().typed<DomainEvent>()
        bus.subscribeStatic(TypedBusStaticHandler::class.java)
        bus.post(DomainA())
        assertEquals(true, TypedBusStaticHandler.called)

        TypedBusStaticHandler.called = false
        bus.unsubscribeStatic(TypedBusStaticHandler::class.java)
        bus.post(DomainA())
        assertFalse(TypedBusStaticHandler.called, "handler should have been removed")
    }
}
