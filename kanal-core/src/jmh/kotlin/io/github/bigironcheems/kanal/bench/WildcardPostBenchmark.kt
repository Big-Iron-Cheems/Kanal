package io.github.bigironcheems.kanal.bench

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Priority
import io.github.bigironcheems.kanal.Subscribe
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Measures wildcard (`subscribeAll`) dispatch cost in isolation and in combination
 * with typed handlers.
 *
 * Three scenarios, all parameterised by [handlerCount]:
 *
 * - [wildcardOnly]: [handlerCount] wildcard handlers, zero typed handlers.
 *   Isolates the raw cost of iterating the wildcard portion of the dispatch list.
 *
 * - [typedOnly]: [handlerCount] typed handlers, zero wildcards.
 *   Baseline for comparison; should match [PostThroughputBenchmark.busPost].
 *
 * - [mixedTypedAndWildcard]: [handlerCount] typed handlers plus [handlerCount]
 *   wildcard handlers interleaved at [Priority.HIGHEST] and [Priority.LOWEST].
 *   Verifies that priority interleaving adds no per-post overhead beyond the
 *   larger handler count; dispatch list is built once and cached.
 *
 * All handlers do minimal work (`count++`) to keep handler cost out of the measurement.
 */
@Suppress("unused")
@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, warmups = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class WildcardPostBenchmark {

    /** Number of handlers in each slot (typed, wildcard, or both). */
    @Param("1", "4", "16", "64")
    var handlerCount: Int = 0

    private lateinit var busWildcardOnly: EventBus
    private lateinit var busTypedOnly: EventBus
    private lateinit var busMixed: EventBus

    @Setup(Level.Trial)
    fun setup() {
        busWildcardOnly = EventBus()
        busTypedOnly = EventBus()
        busMixed = EventBus()

        // Wildcard-only: no typed handlers
        repeat(handlerCount) { i ->
            val priority = if (i % 2 == 0) Priority.HIGHEST else Priority.LOWEST
            busWildcardOnly.subscribeAll(priority) { e -> (e as BenchEvent).count++ }
        }

        // Typed-only: annotation-based handlers
        repeat(handlerCount) { busTypedOnly.subscribe(TypedSub()) }

        // Mixed: equal numbers of typed and wildcard handlers, wildcards at
        // HIGHEST and LOWEST so they interleave with typed handlers at NORMAL.
        // The dispatch list is sorted once on first post; subsequent posts pay
        // only the iteration cost.
        repeat(handlerCount) { busMixed.subscribe(TypedSub()) }
        repeat(handlerCount / 2 + 1) { busMixed.subscribeAll(Priority.HIGHEST) { e -> (e as BenchEvent).count++ } }
        repeat(handlerCount / 2 + 1) { busMixed.subscribeAll(Priority.LOWEST) { e -> (e as BenchEvent).count++ } }

        // Warm dispatch caches
        busWildcardOnly.post(BenchEvent())
        busTypedOnly.post(BenchEvent())
        busMixed.post(BenchEvent())
    }

    /** [handlerCount] wildcard handlers, no typed handlers. */
    @Benchmark
    fun wildcardOnly(bh: Blackhole) {
        val e = BenchEvent()
        busWildcardOnly.post(e)
        bh.consume(e.count)
    }

    /** [handlerCount] typed handlers, no wildcards. Baseline for comparison with [mixedTypedAndWildcard]. */
    @Benchmark
    fun typedOnly(bh: Blackhole) {
        val e = BenchEvent()
        busTypedOnly.post(e)
        bh.consume(e.count)
    }

    /**
     * [handlerCount] typed handlers plus [handlerCount] wildcard handlers interleaved by priority.
     *
     * Expected: cost scales linearly with total handler count, identical slope to [typedOnly]
     * and [wildcardOnly]. No extra overhead from priority-interleaving because the merged,
     * sorted dispatch list is built once and cached.
     */
    @Benchmark
    fun mixedTypedAndWildcard(bh: Blackhole) {
        val e = BenchEvent()
        busMixed.post(e)
        bh.consume(e.count)
    }

    class BenchEvent : Event {
        var count = 0
    }

    class TypedSub {
        @Subscribe
        fun on(e: BenchEvent) {
            e.count++
        }
    }
}
