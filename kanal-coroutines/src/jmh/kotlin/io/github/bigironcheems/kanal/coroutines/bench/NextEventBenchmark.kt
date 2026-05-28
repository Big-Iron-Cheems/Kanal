package io.github.bigironcheems.kanal.coroutines.bench

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.coroutines.nextEvent
import io.github.bigironcheems.kanal.coroutines.nextEventOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Measures the latency of [nextEvent] and [nextEventOrNull] from the perspective
 * of a thread that posts an event and a coroutine that awaits it.
 *
 * - [nextEventNoFilter]: plain `nextEvent` with no predicate.
 * - [nextEventWithPredicate]: `nextEvent` with a predicate that matches on first attempt.
 * - [nextEventWithPredicateSkip]: `nextEvent` with a predicate that skips the first event
 *   and matches on the second; measures predicate evaluation overhead under misses.
 * - [nextEventOrNullHit]: `nextEventOrNull` that receives an event before timeout.
 * - [nextEventOrNullMiss]: `nextEventOrNull` that times out; measures timeout path cost.
 *   Uses a very short timeout (1ms) to avoid slowing down the benchmark suite.
 *
 * All benchmarks use [runBlocking] as the coroutine entry point. The measured cost
 * includes the [runBlocking] bridge, coroutine suspension, event dispatch, and resume.
 *
 * [yield] is used after [async] to ensure the coroutine has started and registered
 * its subscription before the event is posted.
 *
 * Two forks are used to reduce variance from OS scheduler timing on the hit-path
 * benchmarks, which showed large confidence intervals in single-fork runs.
 */
@Suppress("unused")
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 6, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, warmups = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class NextEventBenchmark {

    private lateinit var bus: EventBus
    private val matchingEvent = BenchEvent(value = 200)
    private val nonMatchingEvent = BenchEvent(value = 50)

    @Setup(Level.Trial)
    fun setup() {
        bus = EventBus()
    }

    /** Plain `nextEvent` with no predicate — baseline for single-event await. */
    @Benchmark
    fun nextEventNoFilter(bh: Blackhole) = runBlocking {
        val deferred = async { bus.nextEvent<BenchEvent>() }
        yield()
        bh.consume(bus.post(matchingEvent))
        bh.consume(deferred.await())
    }

    /** `nextEvent` with a predicate that matches on the first event. */
    @Benchmark
    fun nextEventWithPredicate(bh: Blackhole) = runBlocking {
        val deferred = async { bus.nextEvent<BenchEvent> { it.value > 100 } }
        yield()
        bh.consume(bus.post(matchingEvent))
        bh.consume(deferred.await())
    }

    /**
     * `nextEvent` with a predicate that skips the first event and matches on the second.
     * Measures overhead of predicate misses: extra subscribe round-trips before resume.
     */
    @Benchmark
    fun nextEventWithPredicateSkip(bh: Blackhole) = runBlocking {
        val deferred = async { bus.nextEvent<BenchEvent> { it.value > 100 } }
        yield()
        bh.consume(bus.post(nonMatchingEvent))
        bh.consume(bus.post(matchingEvent))
        bh.consume(deferred.await())
    }

    /** `nextEventOrNull` that receives an event before timeout — hit path. */
    @Benchmark
    fun nextEventOrNullHit(bh: Blackhole) = runBlocking {
        val deferred = async { bus.nextEventOrNull<BenchEvent>(5.seconds) }
        yield()
        bh.consume(bus.post(matchingEvent))
        bh.consume(deferred.await())
    }

    /**
     * `nextEventOrNull` that times out — miss path.
     * Uses a 1ms timeout to keep benchmark duration reasonable while still
     * exercising the full timeout/cancellation code path.
     */
    @Benchmark
    fun nextEventOrNullMiss(bh: Blackhole) = runBlocking {
        bh.consume(bus.nextEventOrNull<BenchEvent>(1L.milliseconds))
    }

    class BenchEvent(val value: Int = 0) : Event
}
