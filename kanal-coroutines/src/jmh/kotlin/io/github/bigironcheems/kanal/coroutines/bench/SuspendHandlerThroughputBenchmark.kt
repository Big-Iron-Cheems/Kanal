package io.github.bigironcheems.kanal.coroutines.bench

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Subscribe
import io.github.bigironcheems.kanal.coroutines.SuspendHandlerBehaviour
import io.github.bigironcheems.kanal.coroutines.suspendHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Measures `post` throughput when handlers are registered via [suspendHandler]
 * under each [SuspendHandlerBehaviour].
 *
 * The benchmark posts events synchronously and measures how fast the bus can
 * dispatch to suspend handlers. Note that the suspend handlers themselves run
 * asynchronously on the provided scope; what is measured here is the cost of
 * the dispatch call (registering the launch) not handler completion.
 *
 * A dedicated single-thread executor is used as the dispatcher to avoid
 * cross-iteration interference from lingering coroutines on [Dispatchers.Default].
 * This isolates the dispatch-call cost (the AtomicReference checks, coroutine
 * launch scheduling) from thread-pool contention.
 *
 * - [parallelDispatch]: [SuspendHandlerBehaviour.Parallel] — launches a new
 *   coroutine for every event.
 * - [discardIfBusyDispatch]: [SuspendHandlerBehaviour.DiscardIfBusy] — checks
 *   active job before launching; cheapest when handler is still running.
 * - [replaceLatestDispatch]: [SuspendHandlerBehaviour.ReplaceLatest] — cancels
 *   previous job and launches a new one; measures cancellation overhead.
 * - [baselineDirectSubscribe]: plain synchronous `@Subscribe` handler as a
 *   baseline to isolate coroutine launch overhead vs raw dispatch cost.
 *
 * [handlerCount] controls how many suspend handlers are registered per behaviour.
 */
@Suppress("unused")
@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, warmups = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class SuspendHandlerThroughputBenchmark {

    @Param("1", "4")
    var handlerCount: Int = 0

    private lateinit var parallelBus: EventBus
    private lateinit var discardBus: EventBus
    private lateinit var replaceBus: EventBus
    private lateinit var baselineBus: EventBus
    private lateinit var supervisorJob: Job
    private lateinit var scope: CoroutineScope
    private lateinit var executor: ExecutorService
    private val event = BenchEvent()

    @Setup(Level.Trial)
    fun setup() {
        executor = Executors.newSingleThreadExecutor()
        supervisorJob = SupervisorJob()
        scope = CoroutineScope(executor.asCoroutineDispatcher() + supervisorJob)

        parallelBus = EventBus()
        discardBus = EventBus()
        replaceBus = EventBus()
        baselineBus = EventBus()

        repeat(handlerCount) {
            parallelBus.suspendHandler<BenchEvent>(
                behaviour = SuspendHandlerBehaviour.Parallel,
                scope = scope
            ) { e -> e.count.incrementAndGet() }

            discardBus.suspendHandler<BenchEvent>(
                behaviour = SuspendHandlerBehaviour.DiscardIfBusy,
                scope = scope
            ) { e -> e.count.incrementAndGet() }

            replaceBus.suspendHandler<BenchEvent>(
                behaviour = SuspendHandlerBehaviour.ReplaceLatest,
                scope = scope
            ) { e -> e.count.incrementAndGet() }

            baselineBus.subscribe(BaselineSub())
        }

        // warm dispatch caches
        parallelBus.post(event)
        discardBus.post(event)
        replaceBus.post(event)
        baselineBus.post(event)
    }

    @TearDown(Level.Trial)
    fun teardown() {
        supervisorJob.cancel()
        executor.shutdown()
    }

    /**
     * Baseline: plain synchronous handler with no coroutine involvement.
     * Delta between this and the suspend variants isolates coroutine launch cost.
     */
    @Benchmark
    fun baselineDirectSubscribe(bh: Blackhole): BenchEvent {
        bh.consume(baselineBus.post(event))
        return event
    }

    /** [SuspendHandlerBehaviour.Parallel]: new coroutine per event regardless of prior state. */
    @Benchmark
    fun parallelDispatch(bh: Blackhole): BenchEvent {
        bh.consume(parallelBus.post(event))
        return event
    }

    /** [SuspendHandlerBehaviour.DiscardIfBusy]: skip if prior coroutine still active. */
    @Benchmark
    fun discardIfBusyDispatch(bh: Blackhole): BenchEvent {
        bh.consume(discardBus.post(event))
        return event
    }

    /** [SuspendHandlerBehaviour.ReplaceLatest]: cancel prior coroutine, launch new one. */
    @Benchmark
    fun replaceLatestDispatch(bh: Blackhole): BenchEvent {
        bh.consume(replaceBus.post(event))
        return event
    }

    class BenchEvent : Event {
        val count = AtomicInteger(0)
    }

    class BaselineSub {
        @Subscribe
        fun on(e: BenchEvent) {
            e.count.incrementAndGet()
        }
    }
}
