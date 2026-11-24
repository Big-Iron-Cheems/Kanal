package io.github.bigironcheems.kanal.coroutines

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.isListening
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies that `nextEvent` / `nextEventOrNull` leave no handler registered on the bus on
 * *every* exit path: success, cancellation, timeout, and races between them.
 *
 * Observable used throughout: [isListening]. These tests use real time and [runBlocking]
 * rather than `runTest` because the race tests need real threads; `runTest` is single-threaded
 * and virtual-time, so it cannot exercise the concurrent paths.
 *
 * Every test is bounded by [withTimeout] so a regression fails instead of hanging the build.
 */
class EventBusNextCleanupTest {

    class SimpleEvent(val value: Int = 0) : Event

    /** Spins (cooperatively) until a handler for [SimpleEvent] is registered. */
    private suspend fun EventBus.awaitListening() {
        while (!isListening<SimpleEvent>()) delay(1.milliseconds)
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) =
        runBlocking { withTimeout(30.seconds) { block() } }

    // success

    @Test
    fun `nextEvent unregisters its handler after the matching event`() = test {
        val bus = EventBus()
        val waiter = async { bus.nextEvent<SimpleEvent>() }
        bus.awaitListening()

        val event = SimpleEvent()
        bus.post(event)

        assertSame(event, waiter.await())
        assertFalse(bus.isListening<SimpleEvent>(), "handler leaked after successful nextEvent")
    }

    @Test
    fun `nextEvent with predicate stays registered across misses and unregisters on the match`() = test {
        val bus = EventBus()
        val waiter = async { bus.nextEvent<SimpleEvent> { it.value > 10 } }
        bus.awaitListening()

        bus.post(SimpleEvent(1))
        delay(10.milliseconds)
        assertTrue(bus.isListening<SimpleEvent>(), "handler must survive a predicate miss")
        assertFalse(waiter.isCompleted)

        bus.post(SimpleEvent(11))
        assertEquals(11, waiter.await().value)
        assertFalse(bus.isListening<SimpleEvent>(), "handler leaked after predicate match")
    }

    @Test
    fun `events posted after the first match are not delivered and leave no handler`() = test {
        val bus = EventBus()
        val waiter = async { bus.nextEvent<SimpleEvent>() }
        bus.awaitListening()

        val first = SimpleEvent(1)
        bus.post(first)
        bus.post(SimpleEvent(2))

        assertSame(first, waiter.await())
        assertFalse(bus.isListening<SimpleEvent>())
    }

    @Test
    fun `nextEventOrNull unregisters its handler on a hit`() = test {
        val bus = EventBus()
        val waiter = async { bus.nextEventOrNull<SimpleEvent>(5.seconds) }
        bus.awaitListening()

        bus.post(SimpleEvent(7))

        assertEquals(7, waiter.await()?.value)
        assertFalse(bus.isListening<SimpleEvent>())
    }

    // cancellation and timeout

    @Test
    fun `nextEvent unregisters its handler on cancellation`() = test {
        val bus = EventBus()
        val waiter = launch { bus.nextEvent<SimpleEvent>() }
        bus.awaitListening()

        waiter.cancelAndJoin()

        assertFalse(bus.isListening<SimpleEvent>(), "handler leaked after cancellation")
    }

    @Test
    fun `nextEventOrNull unregisters its handler on timeout`() = test {
        val bus = EventBus()

        assertNull(bus.nextEventOrNull<SimpleEvent>(50.milliseconds))

        assertFalse(bus.isListening<SimpleEvent>(), "handler leaked after timeout")
    }

    @Test
    fun `nextEvent does not subscribe when cancelled before it starts`() = test {
        val bus = EventBus()
        val waiter = launch(start = CoroutineStart.LAZY) { bus.nextEvent<SimpleEvent>() }

        waiter.cancelAndJoin()

        assertFalse(bus.isListening<SimpleEvent>())
    }

    // concurrency

    @Test
    fun `racing cancellation against delivery leaves no handler and raises no error`() = test {
        val errors = CopyOnWriteArrayList<Throwable>()
        val bus = EventBus { errors += it }

        repeat(2_000) { i ->
            val waiter = launch(Dispatchers.Default) { bus.nextEvent<SimpleEvent>() }
            bus.awaitListening()

            // Post and cancel start together; whichever wins, the end state must be identical.
            val poster = launch(Dispatchers.Default) { bus.post(SimpleEvent(i)) }
            val canceller = launch(Dispatchers.Default) { waiter.cancel() }
            joinAll(waiter, poster, canceller)

            assertFalse(bus.isListening<SimpleEvent>(), "handler leaked at iteration $i")
        }
        assertTrue(errors.isEmpty(), "unexpected errors routed to exceptionHandler: $errors")
    }
}
