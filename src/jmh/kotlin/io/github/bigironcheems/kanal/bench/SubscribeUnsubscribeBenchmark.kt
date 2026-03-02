package io.github.bigironcheems.kanal.bench

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Subscribe
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

/**
 * Measures the cost of subscribing and unsubscribing handlers.
 *
 * A fresh [EventBus] is created per iteration ([Level.Iteration]) so each
 * measurement starts from an empty bus. However, the shared `invokerFactoryCache`
 * (keyed by [java.lang.reflect.Method]) persists across iterations; so after the
 * first warmup iteration the `LambdaMetafactory` cost is never paid again within
 * a single JVM run. All measurements here reflect the **warm-cache** path.
 *
 * ### Cold cost
 * The first-ever subscribe for a given method (cache miss) is significantly more
 * expensive than subsequent calls; `LambdaMetafactory.metafactory` is paid once
 * per method per JVM lifetime.
 *
 * Results are in microseconds because even the warm path involves reflection scanning
 * and map lookups; an order of magnitude slower than a `post` call.
 */
@Suppress("unused")
@State(Scope.Thread)
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, warmups = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class SubscribeUnsubscribeBenchmark {

    private lateinit var bus: EventBus
    private lateinit var pool: Array<InstanceSub>
    private var index = 0

    @Setup(Level.Iteration)
    fun setup() {
        bus = EventBus()
        pool = Array(1000) { InstanceSub() }
        index = 0
    }

    @Benchmark
    fun subscribeInstance(): EventBus {
        bus.subscribe(pool[index % pool.size])
        index++
        return bus   // prevent dead-code elimination of bus itself
    }

    @Benchmark
    fun subscribeThenUnsubscribeInstance(): EventBus {
        val sub = pool[index % pool.size]
        index++
        bus.subscribe(sub)
        bus.unsubscribe(sub)
        return bus
    }

    @Benchmark
    fun subscribeStatic(): EventBus {
        bus.subscribeStatic(StaticSub::class.java)
        return bus
    }

    @Benchmark
    fun subscribeThenUnsubscribeStatic(): EventBus {
        bus.subscribeStatic(StaticSub::class.java)
        bus.unsubscribeStatic(StaticSub::class.java)
        return bus
    }

    class BenchEvent : Event

    class InstanceSub {
        @Subscribe
        fun on(e: BenchEvent) {
            // No-op; we just want to measure the cost of registering the handler, not its execution.
        }
    }

    object StaticSub {
        @JvmStatic
        @Subscribe
        fun on(e: BenchEvent) {
            // No-op; we just want to measure the cost of registering the handler, not its execution.
        }
    }
}
