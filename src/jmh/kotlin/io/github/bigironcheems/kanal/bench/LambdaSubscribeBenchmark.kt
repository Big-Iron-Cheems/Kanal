package io.github.bigironcheems.kanal.bench

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Priority
import io.github.bigironcheems.kanal.TypedEventBus
import io.github.bigironcheems.kanal.subscribe
import io.github.bigironcheems.kanal.typed
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Measures the cost of subscribing a lambda handler via raw vs typed [EventBus].
 *
 * A fresh [EventBus] is created per invocation so list state never accumulates.
 * The `LambdaMetafactory` invoker cache is warm after JMH's warmup iterations
 * since it is keyed by [java.lang.reflect.Method] and shared across all bus instances.
 */
@Suppress("unused")
@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 8, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, warmups = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class LambdaSubscribeBenchmark {

    sealed interface BenchEvent : Event
    class ConcreteBenchEvent : BenchEvent

    /** Subscribe a lambda via the raw bus. */
    @Benchmark
    fun untypedSubscribeLambda(bh: Blackhole): EventBus {
        val bus = EventBus()
        bh.consume(bus.subscribe<ConcreteBenchEvent>(Priority.NORMAL) { })
        return bus
    }

    /** Subscribe a lambda via the typed bus. */
    @Benchmark
    fun typedSubscribeLambda(bh: Blackhole): TypedEventBus<BenchEvent> {
        val bus = EventBus().typed<BenchEvent>()
        bh.consume(bus.subscribe<ConcreteBenchEvent>(Priority.NORMAL) { })
        return bus
    }
}
