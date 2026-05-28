package io.github.bigironcheems.kanal.coroutines

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.isListening
import io.github.bigironcheems.kanal.typed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TypedEventBusCoroutinesTest {

    sealed interface NetworkEvent : Event
    class PacketReceived(val bytes: Int) : NetworkEvent
    class ConnectionLost(val reason: String) : NetworkEvent

    @Test
    fun `asFlow on TypedEventBus emits matching events`() = runTest {
        val bus = EventBus().typed<NetworkEvent>()

        val deferred = async {
            bus.asFlow<PacketReceived>()
                .take(2)
                .toList()
        }

        advanceUntilIdle()

        bus.post(PacketReceived(1))
        bus.post(ConnectionLost("timeout")) // should not appear
        bus.post(PacketReceived(2))

        val results = deferred.await()
        assertEquals(listOf(1, 2), results.map { it.bytes })
    }

    @Test
    fun `asFlow on TypedEventBus unregisters handler on cancellation`() = runTest {
        val bus = EventBus().typed<NetworkEvent>()

        val job = launch {
            bus.asFlow<PacketReceived>().collect { }
        }

        advanceUntilIdle()
        assertTrue(bus.isListening<PacketReceived>())

        job.cancelAndJoin()
        assertFalse(bus.isListening<PacketReceived>())
    }

    @Test
    fun `suspendHandler on TypedEventBus fires for matching events`() = runTest {
        val bus = EventBus().typed<NetworkEvent>()
        val received = mutableListOf<Int>()

        val sub = bus.suspendHandler<PacketReceived>(
            scope = backgroundScope
        ) { e ->
            received += e.bytes
        }

        bus.post(PacketReceived(1))
        bus.post(ConnectionLost("timeout")) // should not trigger handler
        bus.post(PacketReceived(2))
        yield()

        assertEquals(listOf(1, 2), received)
        sub.cancel()
    }

    @Test
    fun `suspendHandler on TypedEventBus unregisters on cancel`() = runTest {
        val bus = EventBus().typed<NetworkEvent>()

        val sub = bus.suspendHandler<PacketReceived>(scope = backgroundScope) { }
        assertTrue(bus.isListening<PacketReceived>())

        sub.cancel()
        assertFalse(bus.isListening<PacketReceived>())
    }
}
