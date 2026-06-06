package io.github.bigironcheems.kanal.bench

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Subscribe
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Measures `post` throughput as the number of registered handlers scales.
 *
 * [handlerCount] is parameterised via [@Param][Param]; JMH runs a separate
 * fork for each value.
 */
@Suppress("unused")
@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, warmups = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class PostThroughputBenchmark {

    /** Number of listeners subscribed before each trial. */
    @Param("1", "4", "16", "64")
    var handlerCount: Int = 0

    private lateinit var bus: EventBus
    private val event = CountingEvent()

    @Setup(Level.Trial)
    fun setup() {
        bus = EventBus()
        repeat(handlerCount) { bus.subscribe(CountingSubscriber()) }
        // warm the dispatch path
        bus.post(event)
    }

    /** Baseline: direct method call with no bus indirection. */
    @Benchmark
    fun directCall(bh: Blackhole) {
        bh.consume(event.count)
    }

    /** The real measurement: full bus dispatch to N handlers. */
    @Benchmark
    fun busPost(bh: Blackhole) {
        bus.post(event)
        bh.consume(event.count)
    }

    class CountingEvent : Event {
        var count = 0
    }

    class CountingSubscriber {
        @Subscribe
        fun on(e: CountingEvent) {
            // Perform a tiny amount of work so the handler body is not optimised away.
            e.count++
        }
    }
}
