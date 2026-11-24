package io.github.bigironcheems.kanal.coroutines

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.isListening
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Stress test for `nextEvent` while events are already in flight on other threads.
 *
 * Requires the dispatch-cache build/invalidate race to be fixed: while that race exists, a
 * handler can be registered yet never invoked, which makes this test hang (and then time out)
 * for reasons unrelated to `nextEvent`. See `DispatchCacheRaceTest` in `kanal-core`.
 */
class EventBusNextConcurrencyTest {

    class SimpleEvent(val value: Int = 0) : Event

    private fun test(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        runBlocking { withTimeout(60.seconds) { block() } }

    @Test
    fun `subscribing under continuous concurrent posting resumes once and leaves no handler`() = test {
        val errors = CopyOnWriteArrayList<Throwable>()
        val bus = EventBus { errors += it }

        // Posters run for the whole test, so each nextEvent registers while events are already in
        // flight. That makes a post land between "handler registered" and "subscription handle
        // published" (the narrowest window in nextEvent) likely rather than a rare accident.
        val running = AtomicBoolean(true)
        val posters = List(2) { p ->
            thread(name = "poster-$p") { while (running.get()) bus.post(SimpleEvent(p)) }
        }
        try {
            repeat(3_000) { round ->
                val winner = async(Dispatchers.Default) { bus.nextEvent<SimpleEvent>() }.await()
                assertTrue(winner.value in 0..1)
                // No waiter is outstanding between rounds, so nothing may still be registered.
                assertFalse(bus.isListening<SimpleEvent>(), "handler leaked at round $round")
            }
        } finally {
            running.set(false)
            posters.forEach { it.join() }
        }
        // A double resume would surface here as an IllegalStateException routed from the handler.
        assertTrue(errors.isEmpty(), "unexpected errors routed to exceptionHandler: $errors")
    }
}
