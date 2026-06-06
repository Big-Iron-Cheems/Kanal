package io.github.bigironcheems.kanal.bench

import io.github.bigironcheems.kanal.Cancellable
import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Subscribe
import io.github.bigironcheems.kanal.Priority
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Compares dispatch cost for cancellable events:
 * - [postNotCancelled]; all N handlers run.
 * - [postCancelledAtFirst]; the first (highest-priority) handler cancels, remaining N-1 are skipped.
 */
@Suppress("unused")
@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, warmups = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class CancellablePostBenchmark {

    @Param("1", "4", "16", "64")
    var handlerCount: Int = 0

    private lateinit var busNormal: EventBus
    private lateinit var busCancelled: EventBus

    @Setup(Level.Trial)
    fun setup() {
        busNormal = EventBus()
        busCancelled = EventBus()

        // Normal bus: only counting handlers
        repeat(handlerCount) { busNormal.subscribe(CountingSub()) }

        // Cancelled bus: one high-priority canceller, rest are counting handlers
        busCancelled.subscribe(CancellerSub())
        repeat(handlerCount) { busCancelled.subscribe(CountingSub()) }
    }

    @Benchmark
    fun postNotCancelled(bh: Blackhole) {
        val e = CancellableTestEvent()
        busNormal.post(e)
        bh.consume(e.count)
    }

    @Benchmark
    fun postCancelledAtFirst(bh: Blackhole) {
        val e = CancellableTestEvent()
        busCancelled.post(e)
        bh.consume(e.count)
        bh.consume(e.isCancelled)
    }

    class CancellableTestEvent : Event, Cancellable {
        override var isCancelled = false
        var count = 0
    }

    class CountingSub {
        @Subscribe
        fun on(e: CancellableTestEvent) {
            e.count++
        }
    }

    class CancellerSub {
        @Subscribe(priority = Priority.HIGHEST)
        fun on(e: CancellableTestEvent) {
            e.cancel()
        }
    }
}
