package io.github.bigironcheems.kanal.coroutines.bench

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Subscribe
import io.github.bigironcheems.kanal.coroutines.postSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Compares dispatch latency across the three posting APIs:
 * - [syncPost]: plain synchronous `post` — baseline with no coroutine overhead.
 * - [suspendPostDefault]: `postSuspend` on [Dispatchers.Default] — measures context switch cost.
 * - [suspendPostUnconfined]: `postSuspend` on [Dispatchers.Unconfined] — measures pure suspend overhead
 *   without a dispatcher hop; closest to `post` in terms of threading.
 * - [asyncPost]: `postAsync` with a virtual-thread executor — measures CompletableFuture overhead
 *   as a reference point for the async path.
 *
 * All coroutine benchmarks block via [runBlocking] to produce a synchronous measurement.
 * The numbers therefore reflect end-to-end latency from the calling thread's perspective,
 * including the [runBlocking] bridge cost.
 *
 * [handlerCount] is parameterised to show how each API scales with handler count.
 */
@Suppress("unused")
@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, warmups = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class PostSuspendBenchmark {

    @Param("1", "4", "16")
    var handlerCount: Int = 0

    private lateinit var bus: EventBus
    private lateinit var asyncBus: EventBus
    private val event = BenchEvent()

    @Setup(Level.Trial)
    fun setup() {
        bus = EventBus()
        asyncBus = EventBus(Executors.newVirtualThreadPerTaskExecutor())
        repeat(handlerCount) {
            bus.subscribe(CountingSub())
            asyncBus.subscribe(CountingSub())
        }
        // warm dispatch caches
        bus.post(event)
        runBlocking { bus.postSuspend(event) }
        asyncBus.postAsync(event).join()
    }

    /** Baseline: plain synchronous post, no coroutines. */
    @Benchmark
    fun syncPost(bh: Blackhole): BenchEvent {
        bh.consume(bus.post(event))
        return event
    }

    /**
     * `postSuspend` on [Dispatchers.Default] — includes a thread hop to the
     * default dispatcher pool and back via [runBlocking].
     */
    @Benchmark
    fun suspendPostDefault(bh: Blackhole): BenchEvent {
        bh.consume(runBlocking { bus.postSuspend(event, Dispatchers.Default) })
        return event
    }

    /**
     * `postSuspend` on [Dispatchers.Unconfined] — no thread hop; runs on the
     * calling thread. Delta vs [syncPost] isolates pure coroutine machinery cost.
     */
    @Benchmark
    fun suspendPostUnconfined(bh: Blackhole): BenchEvent {
        bh.consume(runBlocking { bus.postSuspend(event, Dispatchers.Unconfined) })
        return event
    }

    /**
     * `postAsync` with a virtual-thread executor — reference point for the
     * existing async path. Blocks via [java.util.concurrent.CompletableFuture.join].
     */
    @Benchmark
    fun asyncPost(bh: Blackhole): BenchEvent {
        bh.consume(asyncBus.postAsync(event).join())
        return event
    }

    class BenchEvent : Event {
        var count = 0
    }

    class CountingSub {
        @Subscribe
        fun on(e: BenchEvent) {
            e.count++
        }
    }
}
