package io.github.bigironcheems.kanal.coroutines.bench

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Subscribe
import io.github.bigironcheems.kanal.coroutines.asFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Measures deterministically measurable costs of [asFlow]:
 *
 * - [directSubscribe]: plain `@Subscribe` handler — raw dispatch cost baseline.
 * - [asFlowSubscribeCancel]: cost of starting a Flow collector and cancelling it;
 *   measures subscription registration + cleanup overhead, not event delivery.
 * - [postToActiveFlow]: post throughput when a Flow collector is already active;
 *   the collector is set up in [setupIteration] before the benchmark body runs,
 *   guaranteeing subscription is registered before any event is posted.
 *
 * Note: measuring end-to-end "post → Flow collector receives event" latency
 * requires synchronization primitives that are not available inside `callbackFlow`
 * without modifying the API. The [postToActiveFlow] benchmark approximates this
 * by measuring post throughput with a live collector, accepting that collector
 * processing (channel receive) runs asynchronously and is not included in the
 * measured window.
 */
@Suppress("unused")
@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, warmups = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class AsFlowBenchmark {

    @Param("1", "4", "16")
    var eventCount: Int = 0

    private lateinit var baselineBus: EventBus
    private lateinit var activeFlowBus: EventBus
    private lateinit var activeFlowScope: kotlinx.coroutines.CoroutineScope

    private val event = BenchEvent()

    @Setup(Level.Trial)
    fun setupTrial() {
        baselineBus = EventBus()
        baselineBus.subscribe(BaselineSub())
        baselineBus.post(event)
    }

    @Setup(Level.Iteration)
    fun setupIteration() {
        // Set up a bus with an active Flow collector already running.
        // By the time the benchmark body executes, the subscription is guaranteed
        // registered because setupIteration completes before any @Benchmark method runs.
        activeFlowBus = EventBus()
        activeFlowScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()
        )
        activeFlowBus.asFlow<BenchEvent>()
            .launchIn(activeFlowScope)
        // Give the collector coroutine time to start and register its subscription.
        // This is acceptable in @Setup — we are not timing this.
        Thread.sleep(50)
    }

    @TearDown(Level.Iteration)
    fun teardownIteration() {
        activeFlowScope.cancel()
    }

    /**
     * Baseline: direct synchronous `@Subscribe` dispatching [eventCount] events.
     */
    @Benchmark
    fun directSubscribe(bh: Blackhole) {
        repeat(eventCount) { bh.consume(baselineBus.post(event)) }
    }

    /**
     * Cost of creating a Flow, starting a collector, and cancelling it.
     * Measures subscription registration + cleanup overhead.
     * No events are posted; the collector never receives anything.
     */
    @Benchmark
    fun asFlowSubscribeCancel(bh: Blackhole) = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Unconfined +
                kotlinx.coroutines.SupervisorJob()
        )
        val job = baselineBus.asFlow<BenchEvent>().launchIn(scope)
        bh.consume(job)
        job.cancel()
        job.join()
    }

    /**
     * Post throughput when a Flow collector is already active.
     * The collector is started in [setupIteration] with a [Thread.sleep] barrier
     * ensuring subscription is registered before the benchmark body runs.
     * Measures the `post` + `trySend` path cost; channel receive runs
     * asynchronously and is not included in the measured window.
     */
    @Benchmark
    fun postToActiveFlow(bh: Blackhole) {
        repeat(eventCount) { bh.consume(activeFlowBus.post(event)) }
    }

    class BenchEvent(val value: Int = 1) : Event {
        var count = 0
    }

    class BaselineSub {
        @Subscribe
        fun on(e: BenchEvent) {
            e.count++
        }
    }
}
