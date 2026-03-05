package io.github.bigironcheems.kanal.bench

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Subscribe
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Measures the overhead of supertype dispatch vs exact-type dispatch.
 *
 * Three scenarios:
 * - [postExactMatch]   ; handler registered for the exact concrete type posted (baseline).
 * - [postSupertypeHit] ; handler registered for a superclass; concrete subclass is posted.
 * - [postInterfaceHit] ; handler registered for an interface; implementing class is posted.
 *
 * The first `@Setup` call warms the dispatch cache so measurement reflects the
 * steady-state (cache-hit) cost, not the one-time build cost.
 */
@Suppress("unused")
@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, warmups = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class SupertypeDispatchBenchmark {

    // Event hierarchy

    interface BaseInterface : Event
    open class BaseClass : Event
    class ConcreteFromClass : BaseClass()
    class ConcreteFromInterface : Event, BaseInterface

    // Buses

    private lateinit var busExact: EventBus
    private lateinit var busSuper: EventBus
    private lateinit var busIface: EventBus

    private val exactEvent = ConcreteFromClass()
    private val supertypeEvent = ConcreteFromClass()
    private val ifaceEvent = ConcreteFromInterface()

    @Setup(Level.Trial)
    fun setup() {
        busExact = EventBus()
        busExact.subscribe(ExactSub())
        busExact.post(exactEvent)       // warm dispatch cache

        busSuper = EventBus()
        busSuper.subscribe(SuperSub())
        busSuper.post(supertypeEvent)   // warm dispatch cache

        busIface = EventBus()
        busIface.subscribe(IfaceSub())
        busIface.post(ifaceEvent)       // warm dispatch cache
    }

    /** Baseline: handler type == posted type. */
    @Benchmark
    fun postExactMatch(bh: Blackhole) {
        busExact.post(exactEvent)
        bh.consume(exactEvent)
    }

    /** Handler registered on superclass; concrete subclass is posted. */
    @Benchmark
    fun postSupertypeHit(bh: Blackhole) {
        busSuper.post(supertypeEvent)
        bh.consume(supertypeEvent)
    }

    /** Handler registered on interface; implementing class is posted. */
    @Benchmark
    fun postInterfaceHit(bh: Blackhole) {
        busIface.post(ifaceEvent)
        bh.consume(ifaceEvent)
    }

    // Subscribers

    class ExactSub {
        @Subscribe
        fun on(e: ConcreteFromClass) {
            // No-op; we just want to measure the cost of dispatching to the handler, not its execution.
        }
    }

    class SuperSub {
        @Subscribe
        fun on(e: BaseClass) {
            // No-op; we just want to measure the cost of dispatching to the handler, not its execution.
        }
    }

    class IfaceSub {
        @Subscribe
        fun on(e: BaseInterface) {
            // No-op; we just want to measure the cost of dispatching to the handler, not its execution.
        }
    }
}
