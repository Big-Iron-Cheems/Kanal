package io.github.bigironcheems.kanal.bench

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Subscribe
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Measures the **cold** `post` cost; the one-time price paid on the first `post`
 * of a concrete event type that has never been seen by the bus before.
 *
 * This triggers [SimpleEventBus.buildDispatchList]: a BFS over the event's supertype
 * hierarchy, a merge of all matching listener lists, and a sort by priority. The
 * result is then cached in `dispatchCache` so subsequent posts pay only a
 * [java.util.concurrent.ConcurrentHashMap] lookup.
 *
 * Two scenarios:
 * - [coldPostExact]    ; handler on the exact type; BFS finds 1 type.
 * - [coldPostSupertype]; handler on a superclass; BFS finds 2 types (concrete + super).
 *
 * The bus is recreated each iteration so the cache is always empty at the start of
 * each measurement call.
 */
@Suppress("unused")
@State(Scope.Thread)
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, warmups = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class ColdDispatchBenchmark {

    // Event hierarchy

    open class BaseEvent : Event
    class ConcreteEvent : BaseEvent()

    // State rebuilt per iteration

    private lateinit var busExact: EventBus
    private lateinit var busSuper: EventBus

    private val exactEvent = ConcreteEvent()
    private val supertypeEvent = ConcreteEvent()

    @Setup(Level.Iteration)
    fun setup() {
        busExact = EventBus()
        busExact.subscribe(ExactSub())

        busSuper = EventBus()
        busSuper.subscribe(SuperSub())
    }

    /**
     * Cold post; handler registered on the exact concrete type.
     * `dispatchCache` is empty; BFS finds 1 matching type.
     */
    @Benchmark
    fun coldPostExact(bh: Blackhole) {
        busExact.post(exactEvent)
        bh.consume(exactEvent)
        // Recreate bus so next call is cold again.
        busExact = EventBus().also { it.subscribe(ExactSub()) }
    }

    /**
     * Cold post; handler registered on a superclass.
     * `dispatchCache` is empty; BFS finds 2 types (ConcreteEvent + BaseEvent).
     */
    @Benchmark
    fun coldPostSupertype(bh: Blackhole) {
        busSuper.post(supertypeEvent)
        bh.consume(supertypeEvent)
        busSuper = EventBus().also { it.subscribe(SuperSub()) }
    }

    // Subscribers

    class ExactSub {
        @Subscribe
        fun on(e: ConcreteEvent) {
            // No-op; we just want to measure the cost of dispatching to the handler, not its execution.
        }
    }

    class SuperSub {
        @Subscribe
        fun on(e: BaseEvent) {
            // No-op; we just want to measure the cost of dispatching to the handler, not its execution.
        }
    }
}
