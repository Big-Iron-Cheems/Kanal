package io.github.bigironcheems.kanal.coroutines

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.Priority
import io.github.bigironcheems.kanal.isListening
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EventBusFlowTest {

    class SimpleEvent(val value: Int) : Event
    class OtherEvent : Event

    @Test
    fun `asFlow emits posted events`() = runTest {
        val bus = EventBus()

        val deferred = async {
            bus.asFlow<SimpleEvent>()
                .take(3)
                .toList()
        }

        advanceUntilIdle()

        bus.post(SimpleEvent(1))
        bus.post(SimpleEvent(2))
        bus.post(SimpleEvent(3))

        val results = deferred.await()
        assertEquals(listOf(1, 2, 3), results.map { it.value })
    }

    @Test
    fun `asFlow does not emit events of other types`() = runTest {
        val bus = EventBus()

        val deferred = async {
            bus.asFlow<SimpleEvent>()
                .take(1)
                .toList()
        }

        advanceUntilIdle()

        bus.post(OtherEvent())
        bus.post(SimpleEvent(42))

        val results = deferred.await()
        assertEquals(listOf(42), results.map { it.value })
    }

    @Test
    fun `asFlow unregisters handler on cancellation`() = runTest {
        val bus = EventBus()

        val job = launch {
            bus.asFlow<SimpleEvent>().collect { }
        }

        advanceUntilIdle()
        assertTrue(bus.isListening<SimpleEvent>())

        job.cancelAndJoin()
        assertFalse(bus.isListening<SimpleEvent>())
    }

    @Test
    fun `asFlow respects priority over other flow subscribers`() = runTest {
        val bus = EventBus()
        val order = mutableListOf<String>()

        val deferredLow = async {
            bus.asFlow<SimpleEvent>(Priority.LOW)
                .take(1)
                .collect { order += "flow-low" }
        }

        val deferredHigh = async {
            bus.asFlow<SimpleEvent>(Priority.HIGH)
                .take(1)
                .collect { order += "flow-high" }
        }

        advanceUntilIdle()
        bus.post(SimpleEvent(1))
        deferredHigh.await()
        deferredLow.await()

        // both flow handlers receive via channel; high-priority trySend called first
        assertEquals(listOf("flow-high", "flow-low"), order)
    }
}
