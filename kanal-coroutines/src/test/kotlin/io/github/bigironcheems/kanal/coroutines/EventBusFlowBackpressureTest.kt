package io.github.bigironcheems.kanal.coroutines

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.isListening
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.conflate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Characterization tests for [asFlow] when the producer (`post`) outpaces the collector.
 *
 * `asFlow` is a `callbackFlow` whose handler calls `trySend`. With the default buffer
 * (`Channel.BUFFERED`, 64 slots unless overridden by a system property) a full channel makes
 * `trySend` fail, and the handler ignores the result: the event is dropped silently, newest
 * first. These tests pin that *current* behaviour so any change to the overflow policy is a
 * deliberate, visible decision. They do not assert that the behaviour is desirable.
 */
class EventBusFlowBackpressureTest {

    class Tick(val seq: Int) : Event

    private companion object {
        const val POSTED = 1_000
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) =
        runBlocking { withTimeout(30.seconds) { block() } }

    /**
     * Collects [Tick]s through [shape] while the collector is blocked on a gate during the whole
     * burst, then releases the gate and waits for the collector to go quiet.
     */
    private suspend fun CoroutineScope.collectUnderBurst(
        shape: (kotlinx.coroutines.flow.Flow<Tick>) -> kotlinx.coroutines.flow.Flow<Tick>,
    ): List<Int> {
        val bus = EventBus()
        val received = CopyOnWriteArrayList<Int>()
        val gate = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Unconfined) {
            shape(bus.asFlow<Tick>()).collect { received += it.seq; gate.await() }
        }
        assertTrue(bus.isListening<Tick>(), "Unconfined launch must have registered the handler")

        repeat(POSTED) { bus.post(Tick(it)) }
        gate.complete(Unit)

        var last = -1
        var stableFor = 0
        while (stableFor < 5) {
            delay(20.milliseconds)
            val size = received.size
            stableFor = if (size == last) stableFor + 1 else 0
            last = size
        }
        job.cancel()
        job.join()
        assertFalse(bus.isListening<Tick>(), "handler leaked after collector cancellation")
        return received.toList()
    }

    @Test
    fun `default asFlow silently drops events when the collector is blocked`() = test {
        val got = collectUnderBurst { it }

        assertTrue(got.size < POSTED, "expected drops, but all $POSTED events arrived")
        assertTrue(got.isNotEmpty(), "collector received nothing")
        // Drop-newest: what survives is exactly the oldest prefix, in order.
        assertEquals(got.indices.toList(), got)
    }

    @Test
    fun `buffer UNLIMITED on the returned flow delivers every event in order`() = test {
        val got = collectUnderBurst { it.buffer(Channel.UNLIMITED) }

        assertEquals((0 until POSTED).toList(), got)
    }

    @Test
    fun `conflate on the returned flow keeps the newest event`() = test {
        val got = collectUnderBurst { it.conflate() }

        // Not a sequence guarantee, but the final element must be the newest one.
        assertEquals(POSTED - 1, got.last())
        assertTrue(got.size < POSTED)
    }

    @Test
    fun `a collector that keeps up receives everything`() = test {
        val bus = EventBus()
        val received = CopyOnWriteArrayList<Int>()
        val job = launch(Dispatchers.Unconfined) {
            bus.asFlow<Tick>().collect { received += it.seq }
        }
        repeat(POSTED) { bus.post(Tick(it)) }
        job.cancel()
        job.join()

        assertEquals((0 until POSTED).toList(), received.toList())
    }
}
