package io.github.bigironcheems.kanal

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression tests for a lost-delivery race: a concurrent `post` could build a dispatch list from the
 * listener lists *before* a `subscribe` and insert it into the dispatch cache *after* that subscribe's
 * eviction ran, leaving a cached list that omits the new handler (`isListening` true, handler never invoked).
 *
 * A lost delivery is permanent until the next mutation, so waiting [DELIVERY_DEADLINE_NANOS] is a
 * sound failure criterion, not a timing guess. The race itself is probabilistic: these tests need
 * posting threads running concurrently, and detection at the old behaviour is likely but not guaranteed
 * per run.
 */
class DispatchCacheRaceTest {

    class Ping : Event

    private companion object {
        const val ROUNDS = 20_000
        const val POSTERS = 2
        const val DELIVERY_DEADLINE_NANOS = 5_000_000_000L
    }

    /** Runs [ROUNDS] register/await-delivery/cancel rounds while posters post [Ping] continuously. */
    private fun hammer(register: (EventBus, AtomicInteger) -> Subscription) {
        val bus = EventBus()
        val running = AtomicBoolean(true)
        val posters = List(POSTERS) {
            thread(name = "poster-$it") { val e = Ping(); while (running.get()) bus.post(e) }
        }
        try {
            repeat(ROUNDS) { round ->
                val hits = AtomicInteger()
                val sub = register(bus, hits)
                val deadline = System.nanoTime() + DELIVERY_DEADLINE_NANOS
                while (hits.get() == 0 && System.nanoTime() < deadline) Thread.onSpinWait()
                assertTrue(hits.get() > 0, "lost delivery at round $round: handler registered but never invoked")
                sub.cancel()
            }
        } finally {
            running.set(false)
            posters.forEach { it.join() }
        }
    }

    @Test
    fun `typed handler subscribed under concurrent posting is eventually invoked`() =
        hammer { bus, hits -> bus.subscribe<Ping> { hits.incrementAndGet() } }

    @Test
    fun `wildcard handler subscribed under concurrent posting is eventually invoked`() =
        hammer { bus, hits -> bus.subscribeAll { if (it is Ping) hits.incrementAndGet() } }
}
