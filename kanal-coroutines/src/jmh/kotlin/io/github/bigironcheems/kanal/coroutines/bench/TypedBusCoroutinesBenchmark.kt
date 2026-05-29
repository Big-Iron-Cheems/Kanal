package io.github.bigironcheems.kanal.coroutines.bench

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.TypedEventBus
import io.github.bigironcheems.kanal.coroutines.*
import io.github.bigironcheems.kanal.typed
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Measures the overhead of the [TypedEventBus] adapter layer with coroutines extensions.
 *
 * Each raw/typed pair uses the same underlying bus where possible — the only
 * difference is whether the call goes through the typed adapter wrapper.
 * Any delta isolates pure adapter delegation cost, expected to be negligible.
 *
 * Flow benchmarks use the [Thread.sleep] barrier pattern from [AsFlowBenchmark]
 * to guarantee the collector is active before posting.
 * nextEvent benchmarks use [CompletableDeferred] as a readiness barrier.
 */
@Suppress("unused")
@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, warmups = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class TypedBusCoroutinesBenchmark {

    sealed interface DomainEvent : Event
    class PacketEvent(val value: Int = 1) : DomainEvent

    // handler buses — stable across iterations
    private lateinit var rawHandlerBus: EventBus
    private lateinit var typedHandlerUnderlying: EventBus
    private lateinit var typedHandlerBus: TypedEventBus<DomainEvent>
    private lateinit var supervisorJob: Job
    private lateinit var scope: CoroutineScope
    private lateinit var executor: java.util.concurrent.ExecutorService

    // nextEvent buses — recreated per iteration
    private lateinit var rawNextBus: EventBus
    private lateinit var typedNextUnderlying: EventBus
    private lateinit var typedNextBus: TypedEventBus<DomainEvent>

    // active flow buses — set up in setupIteration with sleep barrier
    private lateinit var rawFlowBus: EventBus
    private lateinit var typedFlowUnderlying: EventBus
    private lateinit var typedFlowBus: TypedEventBus<DomainEvent>
    private lateinit var flowScope: CoroutineScope

    private val event = PacketEvent()

    @Setup(Level.Trial)
    fun setupTrial() {
        executor = Executors.newSingleThreadExecutor()
        supervisorJob = SupervisorJob()
        scope = CoroutineScope(executor.asCoroutineDispatcher() + supervisorJob)

        rawHandlerBus = EventBus()
        typedHandlerUnderlying = EventBus()
        typedHandlerBus = typedHandlerUnderlying.typed()

        rawHandlerBus.suspendHandler<PacketEvent>(
            behaviour = SuspendHandlerBehaviour.Parallel,
            scope = scope
        ) { }

        typedHandlerBus.suspendHandler<PacketEvent>(
            behaviour = SuspendHandlerBehaviour.Parallel,
            scope = scope
        ) { }

        rawHandlerBus.post(event)
        typedHandlerUnderlying.post(event)
    }

    @Setup(Level.Iteration)
    fun setupIteration() {
        rawNextBus = EventBus()
        typedNextUnderlying = EventBus()
        typedNextBus = typedNextUnderlying.typed()

        rawFlowBus = EventBus()
        typedFlowUnderlying = EventBus()
        typedFlowBus = typedFlowUnderlying.typed()

        flowScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        rawFlowBus.asFlow<PacketEvent>().launchIn(flowScope)
        typedFlowBus.asFlow<PacketEvent>().launchIn(flowScope)
        Thread.sleep(50)
    }

    @TearDown(Level.Iteration)
    fun teardownIteration() {
        flowScope.cancel()
    }

    @TearDown(Level.Trial)
    fun teardownTrial() {
        supervisorJob.cancel()
        executor.shutdown()
    }

    // — asFlow post throughput (collector already active)

    @Benchmark
    fun rawPostToActiveFlow(bh: Blackhole): PacketEvent {
        bh.consume(rawFlowBus.post(event))
        return event
    }

    @Benchmark
    fun typedPostToActiveFlow(bh: Blackhole): PacketEvent {
        bh.consume(typedFlowUnderlying.post(event))
        return event
    }

    // — nextEvent (CompletableDeferred barrier)

    @Benchmark
    fun rawNextEvent(bh: Blackhole) = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val deferred = async {
            ready.complete(Unit)
            rawNextBus.nextEvent<PacketEvent>()
        }
        ready.await()
        rawNextBus.post(event)
        bh.consume(deferred.await())
    }

    @Benchmark
    fun typedNextEvent(bh: Blackhole) = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val deferred = async {
            ready.complete(Unit)
            typedNextBus.nextEvent<PacketEvent>()
        }
        ready.await()
        typedNextBus.post(event)
        bh.consume(deferred.await())
    }

    // — nextEventOrNull hit path

    @Benchmark
    fun rawNextEventOrNull(bh: Blackhole) = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val deferred = async {
            ready.complete(Unit)
            rawNextBus.nextEventOrNull<PacketEvent>(5.seconds)
        }
        ready.await()
        rawNextBus.post(event)
        bh.consume(deferred.await())
    }

    @Benchmark
    fun typedNextEventOrNull(bh: Blackhole) = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val deferred = async {
            ready.complete(Unit)
            typedNextBus.nextEventOrNull<PacketEvent>(5.seconds)
        }
        ready.await()
        typedNextBus.post(event)
        bh.consume(deferred.await())
    }

    // — suspendHandler dispatch throughput

    @Benchmark
    fun rawSuspendHandler(bh: Blackhole): PacketEvent {
        bh.consume(rawHandlerBus.post(event))
        return event
    }

    @Benchmark
    fun typedSuspendHandler(bh: Blackhole): PacketEvent {
        bh.consume(typedHandlerUnderlying.post(event))
        return event
    }
}
