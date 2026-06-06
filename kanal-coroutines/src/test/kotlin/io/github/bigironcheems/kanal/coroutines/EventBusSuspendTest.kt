package io.github.bigironcheems.kanal.coroutines

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.subscribe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class EventBusSuspendTest {

    class SimpleEvent(val value: Int) : Event

    @Test
    fun `postSuspend returns the same event instance`() = runTest {
        val bus = EventBus()
        val event = SimpleEvent(1)
        val returned = bus.postSuspend(event)
        assertSame(event, returned)
    }

    @Test
    fun `postSuspend dispatches to registered handlers`() = runTest {
        val bus = EventBus()
        var received = -1
        bus.subscribe<SimpleEvent> { received = it.value }
        bus.postSuspend(SimpleEvent(42))
        assertEquals(42, received)
    }

    @Test
    fun `postSuspend accepts custom context`() = runTest {
        val bus = EventBus()
        var received = -1
        bus.subscribe<SimpleEvent> { received = it.value }
        bus.postSuspend(SimpleEvent(99), Dispatchers.Unconfined)
        assertEquals(99, received)
    }
}
