package io.github.bigironcheems.kanal.bench

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Priority
import io.github.bigironcheems.kanal.Subscribe
import io.github.bigironcheems.kanal.TypedEventBus
import io.github.bigironcheems.kanal.typed
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Measures the overhead of the [TypedEventBus] adapter layer relative to
 * a plain [EventBus].
 *
 * Because [TypedEventBus] is a thin delegation wrapper with no state of its
 * own, the expectation is that all typed benchmarks are statistically
 * indistinguishable from their untyped equivalents. Any measured delta would
 * indicate unexpected JIT de-optimisation of the delegation path.
 *
 * ### Benchmarks
 *
 * - **`untypedPost` / `typedPost`**; full dispatch to [handlerCount] handlers
 *   through the raw bus vs through the typed view. Both share the same
 *   underlying `SimpleEventBus` instance; only the call site differs.
 *
 * - **`typedPostWithWildcard`**; post through the typed view when a wildcard
 *   handler is also registered. Confirms that wildcard dispatch through the
 *   typed adapter has the same cost as through the raw bus.
 */
@Suppress("unused")
@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, warmups = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class TypedEventBusBenchmark {

    sealed interface BenchEvent : Event
    class ConcreteBenchEvent : BenchEvent

    class BenchSubscriber {
        @Subscribe
        fun on(e: BenchEvent) {
            // No-op; we just want to measure dispatch overhead, not handler execution time.
        }
    }

    @Param("1", "4", "16", "64")
    var handlerCount: Int = 0

    private lateinit var underlying: EventBus
    private lateinit var typedBus: TypedEventBus<BenchEvent>
    private lateinit var untypedBusWildcard: EventBus
    private lateinit var typedBusWildcard: TypedEventBus<BenchEvent>
    private val event = ConcreteBenchEvent()

    @Setup(Level.Trial)
    fun setup() {
        // Shared post benchmarks
        underlying = EventBus()
        typedBus = underlying.typed()
        repeat(handlerCount) {
            underlying.subscribe(BenchSubscriber())
        }
        // Warm both paths
        underlying.post(event)
        typedBus.post(event)

        // Wildcard benchmarks; separate bus to avoid cross-contamination
        untypedBusWildcard = EventBus()
        typedBusWildcard = untypedBusWildcard.typed()
        repeat(handlerCount) { untypedBusWildcard.subscribe(BenchSubscriber()) }
        untypedBusWildcard.subscribeAll(Priority.NORMAL) { /* wildcard observer */ }
        untypedBusWildcard.post(event)
        typedBusWildcard.post(event)
    }

    /** Baseline: post through the raw [EventBus]. */
    @Benchmark
    fun untypedPost(bh: Blackhole): BenchEvent {
        bh.consume(underlying.post(event))
        return event
    }

    /** Post through the [TypedEventBus] adapter; expect identical cost to [untypedPost]. */
    @Benchmark
    fun typedPost(bh: Blackhole): BenchEvent {
        bh.consume(typedBus.post(event))
        return event
    }

    /**
     * Post through the raw bus when a wildcard handler is also registered.
     * Baseline for [typedPostWithWildcard].
     */
    @Benchmark
    fun untypedPostWithWildcard(bh: Blackhole): BenchEvent {
        bh.consume(untypedBusWildcard.post(event))
        return event
    }

    /**
     * Post through the typed view when a wildcard handler is also registered.
     * Expect identical cost to [untypedPostWithWildcard].
     */
    @Benchmark
    fun typedPostWithWildcard(bh: Blackhole): BenchEvent {
        bh.consume(typedBusWildcard.post(event))
        return event
    }
}
