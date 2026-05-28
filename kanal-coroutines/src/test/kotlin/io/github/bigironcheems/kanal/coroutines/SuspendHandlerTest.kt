package io.github.bigironcheems.kanal.coroutines

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.isListening
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class SuspendHandlerTest {

    class SimpleEvent(val value: Int) : Event

    @Test
    fun `Parallel launches a coroutine for every event`() = runTest {
        val bus = EventBus()
        val received = mutableListOf<Int>()

        val sub = bus.suspendHandler<SimpleEvent>(
            behaviour = SuspendHandlerBehaviour.Parallel,
            scope = backgroundScope
        ) { e ->
            received += e.value
        }

        bus.post(SimpleEvent(1))
        bus.post(SimpleEvent(2))
        bus.post(SimpleEvent(3))
        yield()

        assertEquals(listOf(1, 2, 3), received)
        sub.cancel()
    }

    @Test
    fun `DiscardIfBusy skips event when handler is still running`() = runTest {
        val bus = EventBus()
        val received = mutableListOf<Int>()

        val sub = bus.suspendHandler<SimpleEvent>(
            behaviour = SuspendHandlerBehaviour.DiscardIfBusy,
            scope = backgroundScope
        ) { e ->
            delay(1000.milliseconds)
            received += e.value
        }

        bus.post(SimpleEvent(1))
        bus.post(SimpleEvent(2))
        yield()

        assertEquals(0, received.size) // neither completed yet; delay(1000) still running
        sub.cancel()
    }

    @Test
    fun `ReplaceLatest cancels previous coroutine on new event`() = runTest {
        val bus = EventBus()
        val received = mutableListOf<Int>()

        val sub = bus.suspendHandler<SimpleEvent>(
            behaviour = SuspendHandlerBehaviour.ReplaceLatest,
            scope = backgroundScope
        ) { e ->
            delay(1000.milliseconds)
            received += e.value
        }

        bus.post(SimpleEvent(1))
        yield()
        bus.post(SimpleEvent(2))
        yield()

        assertEquals(0, received.size)
        sub.cancel()
    }

    @Test
    fun `cancelling subscription stops handler from firing`() = runTest {
        val bus = EventBus()
        var count = 0

        val sub = bus.suspendHandler<SimpleEvent>(
            scope = backgroundScope
        ) { count++ }

        bus.post(SimpleEvent(1))
        yield()
        assertEquals(1, count)

        sub.cancel()
        bus.post(SimpleEvent(2))
        yield()
        assertEquals(1, count)
    }

    @Test
    fun `cancelling subscription unregisters from bus`() = runTest {
        val bus = EventBus()
        val sub = bus.suspendHandler<SimpleEvent>(scope = backgroundScope) { }
        assertTrue(bus.isListening<SimpleEvent>())
        sub.cancel()
        assertFalse(bus.isListening<SimpleEvent>())
    }
}
