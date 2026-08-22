package io.github.bigironcheems.kanal.bench

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Subscribe
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Measures `SimpleEventBus`'s lock-contention behaviour under concurrent posting and
 * mutation. No other benchmark in this suite exercises this: [PostThroughputBenchmark]
 * and [SubscribeUnsubscribeBenchmark] are both effectively single-threaded
 * (`@State(Scope.Thread)`), so neither can observe `synchronized(lock)` contention on
 * the mutation path or its effect on the lock-free `post` read path.
 *
 * Uses JMH's `@Group` mechanism: poster and mutator threads run *concurrently* within
 * the same iteration against one shared `EventBus` (`@State(Scope.Benchmark)`).
 *
 * Two scenarios, selected via [disjointTypes]:
 * - `true`  (disjoint) - posters post [TypeA]; mutators subscribe/unsubscribe [TypeB].
 *   Isolates whether unrelated-type mutation still contends with posting. Under the
 *   current single global `lock` plus global `dispatchCache.clear()`, it should.
 * - `false` (same-type) - posters and mutators both operate on [TypeA]. The realistic
 *   worst case: every mutation invalidates the exact cache entry posters are reading,
 *   forcing a cold dispatch-list rebuild on the next post of that type.
 *
 * Compare [postThroughput] against the single-threaded [PostThroughputBenchmark] to
 * isolate the contention delta from raw dispatch cost, and [mutateThroughput] against
 * [SubscribeUnsubscribeBenchmark] likewise.
 *
 * Thread split is 4 posters : 1 mutator, reflecting the read-heavy workload the current
 * design targets (see `design.typ`, "Why CopyOnWriteArrayList"). If results are
 * inconclusive at this ratio, rerun with the group thread counts adjusted.
 */
@Suppress("unused")
@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 4, warmups = 1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class ConcurrentPostVsMutateBenchmark {

    /** `true`: mutators touch a different event type than posters. `false`: same type. */
    @Param("true", "false")
    var disjointTypes: Boolean = true

    private lateinit var bus: EventBus
    private val postedEvent = TypeA()

    // Distinguishes mutator-thread iterations only to keep the JIT from folding the
    // subscribe/unsubscribe pair into a no-op; not otherwise meaningful.
    private val mutationCounter = AtomicInteger(0)

    @Setup(Level.Trial)
    fun setup() {
        bus = EventBus()
        // Seed steady-state handlers so post() does real dispatch-list work,
        // not an empty-list fast path.
        bus.subscribe(TypeAHandler())
        if (disjointTypes) bus.subscribe(TypeBHandler())
        bus.post(postedEvent) // warm the dispatch cache for the posted type
    }

    /**
     * Control: four concurrent poster threads, but no mutation.
     *
     * This measures the cost of concurrent reads against a stable, already-warmed
     * dispatch cache. Comparing it with `contended.postThroughput` separates
     * ordinary 4-thread contention/oversubscription from invalidation caused by
     * concurrent mutation.
     */
    @Benchmark
    @Group("uncontended")
    @GroupThreads(4)
    fun postUncontended(bh: Blackhole) {
        bh.consume(bus.post(postedEvent))
    }

    /** Posting thread: repeatedly posts [TypeA], reading whatever registry state is current. */
    @Benchmark
    @Group("contended")
    @GroupThreads(4)
    fun postThroughput(bh: Blackhole) {
        bh.consume(bus.post(postedEvent))
    }

    /**
     * Mutator thread: repeatedly subscribes then unsubscribes a fresh handler instance
     * on [TypeA] (same-type scenario) or [TypeB] (disjoint scenario), per [disjointTypes].
     * Each iteration invalidates `dispatchCache` twice (subscribe + unsubscribe),
     * contending for `lock` against the concurrent posting threads above. Net handler
     * count returns to the seeded baseline after each iteration; the identity-registration
     * maps do not grow unboundedly across iterations.
     */
    @Benchmark
    @Group("contended")
    @GroupThreads(1)
    fun mutateThroughput(bh: Blackhole) {
        bh.consume(mutationCounter.incrementAndGet())
        if (disjointTypes) {
            val handler = TypeBHandler()
            bus.subscribe(handler)
            bus.unsubscribe(handler)
        } else {
            val handler = TypeAHandler()
            bus.subscribe(handler)
            bus.unsubscribe(handler)
        }
    }

    class TypeA : Event
    class TypeB : Event

    class TypeAHandler {
        @Subscribe
        fun on(e: TypeA) {
            // No-op; measures dispatch-list traversal cost only.
        }
    }

    class TypeBHandler {
        @Subscribe
        fun on(e: TypeB) {
            // No-op.
        }
    }
}
