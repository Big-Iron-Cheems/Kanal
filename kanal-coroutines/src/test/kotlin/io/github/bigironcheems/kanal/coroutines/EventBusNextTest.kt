package io.github.bigironcheems.kanal.coroutines

import io.github.bigironcheems.kanal.Event
import io.github.bigironcheems.kanal.EventBus
import io.github.bigironcheems.kanal.typed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class EventBusNextTest {

    sealed interface NetworkEvent : Event
    class PacketReceived(val bytes: Int) : NetworkEvent
    class ConnectionLost : NetworkEvent

    // nextEvent

    @Test
    fun `nextEvent returns the correct event instance`() = runTest {
        val bus = EventBus()
        val event = PacketReceived(42)

        val deferred = async { bus.nextEvent<PacketReceived>() }

        advanceUntilIdle()
        bus.post(event)

        assertSame(event, deferred.await())
    }

    @Test
    fun `nextEvent ignores events of other types`() = runTest {
        val bus = EventBus()

        val deferred = async { bus.nextEvent<PacketReceived>() }

        advanceUntilIdle()
        bus.post(ConnectionLost())
        bus.post(PacketReceived(99))

        assertEquals(99, deferred.await().bytes)
    }

    @Test
    fun `nextEvent applies predicate and skips non-matching events`() = runTest {
        val bus = EventBus()

        val deferred = async {
            bus.nextEvent<PacketReceived> { it.bytes > 100 }
        }

        advanceUntilIdle()
        bus.post(PacketReceived(50))
        bus.post(PacketReceived(200))

        assertEquals(200, deferred.await().bytes)
    }

    @Test
    fun `nextEvent unregisters handler after first matching event`() = runTest {
        val bus = EventBus()

        val deferred = async { bus.nextEvent<PacketReceived>() }

        advanceUntilIdle()
        bus.post(PacketReceived(1))
        deferred.await()

        assertNull(bus.nextEventOrNull<PacketReceived>(0.seconds))
    }

    @Test
    fun `nextEvent unregisters handler on cancellation`() = runTest {
        val bus = EventBus()

        val job = async { bus.nextEvent<PacketReceived>() }

        advanceUntilIdle()
        job.cancel()
        job.join()

        assertNull(bus.nextEventOrNull<PacketReceived>(0.seconds))
    }

    // nextEventOrNull

    @Test
    fun `nextEventOrNull returns event when received before timeout`() = runTest {
        val bus = EventBus()
        val event = PacketReceived(42)

        val deferred = async {
            bus.nextEventOrNull<PacketReceived>(5.seconds)
        }

        runCurrent()
        bus.post(event)
        runCurrent()

        assertSame(event, deferred.await())
    }

    @Test
    fun `nextEventOrNull returns null on timeout`() = runTest {
        val bus = EventBus()

        val deferred = async {
            bus.nextEventOrNull<PacketReceived>(1.seconds)
        }

        runCurrent()
        advanceTimeBy(2.seconds)
        runCurrent()

        assertNull(deferred.await())
    }

    @Test
    fun `nextEventOrNull applies predicate`() = runTest {
        val bus = EventBus()

        val deferred = async {
            bus.nextEventOrNull<PacketReceived>(5.seconds) { it.bytes > 100 }
        }

        runCurrent()
        bus.post(PacketReceived(50))
        runCurrent()
        bus.post(PacketReceived(150))
        runCurrent()

        assertEquals(150, deferred.await()?.bytes)
    }

    // TypedEventBus overloads

    @Test
    fun `nextEvent on TypedEventBus resumes with matching event`() = runTest {
        val bus = EventBus().typed<NetworkEvent>()

        val deferred = async { bus.nextEvent<PacketReceived>() }

        advanceUntilIdle()
        bus.post(ConnectionLost())
        bus.post(PacketReceived(7))

        assertEquals(7, deferred.await().bytes)
    }

    @Test
    fun `nextEventOrNull on TypedEventBus returns null on timeout`() = runTest {
        val bus = EventBus().typed<NetworkEvent>()

        val deferred = async {
            bus.nextEventOrNull<PacketReceived>(1.seconds)
        }

        runCurrent()
        advanceTimeBy(2.seconds)
        runCurrent()

        assertNull(deferred.await())
    }
}
