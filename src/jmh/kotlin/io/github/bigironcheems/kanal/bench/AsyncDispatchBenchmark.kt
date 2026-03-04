package io.github.bigironcheems.kanal.bench

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Priority
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Compares throughput of `postAsync` (virtual-thread executor) vs sync `post`
 * across a range of async vs sync handler mixes.
 *
 * Scenarios (parameterised via [scenario]):
 * - `ALL_SYNC`  - all handlers sync; both buses have [handlerCount] sync handlers.
 * - `ALL_ASYNC` - async bus has [handlerCount] async handlers; sync bus has the same count of
 *                 sync handlers as a comparable baseline (empty-dispatch is not a useful baseline).
 * - `MIXED`     - async bus has [handlerCount] HIGH-async + [handlerCount] LOW-sync handlers;
 *                 sync bus has the equivalent total count of sync handlers.
 *
 * [syncPost] always measures a fully synchronous bus with an equivalent handler count so the
 * numbers are comparable across scenarios.
 */
@Suppress("unused")
@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, warmups = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class AsyncDispatchBenchmark {

    enum class Scenario { ALL_SYNC, ALL_ASYNC, MIXED }

    @Param("ALL_SYNC", "ALL_ASYNC", "MIXED")
    var scenario: Scenario = Scenario.ALL_SYNC

    /** Number of handlers per category (sync or async). */
    @Param("1", "4")
    var handlerCount: Int = 0

    private lateinit var syncBus: EventBus
    private lateinit var asyncBus: EventBus

    @Setup(Level.Trial)
    fun setup() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()

        syncBus = EventBus()           // no executor, pure sync baseline
        asyncBus = EventBus(executor)

        // syncBus always gets the equivalent total handler count for a fair baseline.
        val totalSyncCount = when (scenario) {
            Scenario.MIXED -> handlerCount * 2   // MIXED has handlerCount async + handlerCount sync
            else -> handlerCount
        }
        repeat(totalSyncCount) {
            syncBus.subscribe(BenchEvent::class.java, Priority.NORMAL) { e: BenchEvent -> e.count++ }
        }

        when (scenario) {
            Scenario.ALL_SYNC -> repeat(handlerCount) {
                asyncBus.subscribe(BenchEvent::class.java, Priority.NORMAL, false) { e: BenchEvent -> e.count++ }
            }
            Scenario.ALL_ASYNC -> repeat(handlerCount) {
                asyncBus.subscribe(BenchEvent::class.java, Priority.NORMAL, true) { e: BenchEvent -> e.count++ }
            }
            Scenario.MIXED -> repeat(handlerCount) {
                asyncBus.subscribe(BenchEvent::class.java, Priority.HIGH, true) { e: BenchEvent -> e.count++ }
                asyncBus.subscribe(BenchEvent::class.java, Priority.LOW, false) { e: BenchEvent -> e.count++ }
            }
        }

        // Warm the dispatch cache
        syncBus.post(BenchEvent())
        asyncBus.postAsync(BenchEvent()).join()
    }

    /** Baseline: fully synchronous `post` with no executor. */
    @Benchmark
    fun syncPost(bh: Blackhole): BenchEvent {
        val e = BenchEvent()
        bh.consume(syncBus.post(e))
        return e
    }

    /**
     * `postAsync` on a bus backed by a virtual-thread executor.
     * Blocks on `.join()` to measure total end-to-end dispatch latency.
     */
    @Benchmark
    fun asyncPost(bh: Blackhole): BenchEvent {
        val e = BenchEvent()
        bh.consume(asyncBus.postAsync(e).join())
        return e
    }

    /**
     * Sync `post` on a bus that has an executor but all-sync handlers.
     * Should be close to [syncPost], confirming no overhead is paid when no
     * async handlers exist in the dispatch list.
     */
    @Benchmark
    fun syncPostWithExecutorConfigured(bh: Blackhole): BenchEvent {
        val e = BenchEvent()
        bh.consume(asyncBus.post(e))
        return e
    }

    class BenchEvent : Event {
        var count = 0
    }
}
