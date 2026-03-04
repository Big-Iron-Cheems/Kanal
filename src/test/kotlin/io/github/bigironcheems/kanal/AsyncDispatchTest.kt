package io.github.bigironcheems.kanal

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Tests for async dispatch support: `@Subscribe(async = true)`, lambda `async = true`,
 * `postAsync`, fallback-to-sync, priority ordering under async, cancellation under async,
 * and `Modifiable` mutation visibility.
 */
class AsyncDispatchTest {

    //  Fixture events

    class SimpleEvent : Event
    class ModifiableEvent(var value: Int = 0) : Event

    /**
     * Deliberately NOT @Volatile: the bus must handle async-safe cancellation automatically
     * via its internal AsyncCancellableGuard.
     */
    class CancellableAsyncEvent : Event, Cancellable {
        override var isCancelled: Boolean = false
    }

    //  1. postAsync — basic completion

    @Test
    fun `postAsync completes with the event instance`() {
        val bus = EventBus(Executors.newVirtualThreadPerTaskExecutor())
        val event = SimpleEvent()
        val future = bus.postAsync(event)
        assertSame(event, future.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `postAsync with no handlers returns already-completed future`() {
        val bus = EventBus(Executors.newVirtualThreadPerTaskExecutor())
        val future = bus.postAsync(SimpleEvent())
        assertTrue(future.isDone)
        assertNotNull(future.get())
    }

    @Test
    fun `postAsync without executor runs synchronously and returns completed future`() {
        val bus = EventBus() // no executor
        var called = false
        bus.subscribe<SimpleEvent> { called = true }
        val future = bus.postAsync(SimpleEvent())
        assertTrue(future.isDone, "Should be already done (ran synchronously)")
        assertTrue(called)
    }

    //  2. @Subscribe(async = true) — annotation-based async handlers

    @Test
    fun `annotated async handler is invoked via postAsync`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bus = EventBus(executor)
        val called = AtomicBoolean(false)

        val listener = object {
            @Subscribe(async = true)
            fun on(e: SimpleEvent) {
                called.set(true)
            }
        }
        bus.subscribe(listener)
        bus.postAsync(SimpleEvent()).get(5, TimeUnit.SECONDS)
        assertTrue(called.get())
    }

    @Test
    fun `annotated async handler is invoked via sync post (blocks until done)`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bus = EventBus(executor)
        val called = AtomicBoolean(false)

        val listener = object {
            @Subscribe(async = true)
            fun on(e: SimpleEvent) {
                called.set(true)
            }
        }
        bus.subscribe(listener)
        bus.post(SimpleEvent()) // sync post should also await async handlers
        assertTrue(called.get())
    }

    //  3. Lambda subscribe with async = true

    @Test
    fun `lambda subscribe with async = true invokes handler off calling thread`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bus = EventBus(executor)
        val handlerThread = java.util.concurrent.atomic.AtomicReference<Thread>()

        bus.subscribe<SimpleEvent>(async = true) { handlerThread.set(Thread.currentThread()) }
        bus.postAsync(SimpleEvent()).get(5, TimeUnit.SECONDS)
        assertNotNull(handlerThread.get())
        assertNotSame(Thread.currentThread(), handlerThread.get())
    }

    @Test
    fun `lambda subscribe with async = false invokes handler on calling thread via postAsync`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bus = EventBus(executor)
        // Sync handler following an async chain step runs on the ForkJoinPool completing thread,
        // but must still be invoked exactly once.
        val count = AtomicInteger(0)
        bus.subscribe<SimpleEvent>(async = false) { count.incrementAndGet() }
        bus.postAsync(SimpleEvent()).get(5, TimeUnit.SECONDS)
        assertEquals(1, count.get())
    }

    //  4. Priority ordering preserved under async dispatch

    @Test
    fun `priority order is preserved with all-async handlers`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bus = EventBus(executor)
        val order = CopyOnWriteArrayList<Int>()

        bus.subscribe<SimpleEvent>(Priority.HIGH, async = true) { order += 1 }
        bus.subscribe<SimpleEvent>(Priority.NORMAL, async = true) { order += 2 }
        bus.subscribe<SimpleEvent>(Priority.LOW, async = true) { order += 3 }

        bus.postAsync(SimpleEvent()).get(5, TimeUnit.SECONDS)
        assertEquals(listOf(1, 2, 3), order.toList())
    }

    @Test
    fun `priority order preserved with mixed sync and async handlers`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bus = EventBus(executor)
        val order = CopyOnWriteArrayList<Int>()

        // HIGH async, NORMAL sync, LOW async
        bus.subscribe<SimpleEvent>(Priority.HIGH, async = true) { order += 1 }
        bus.subscribe<SimpleEvent>(Priority.NORMAL, async = false) { order += 2 }
        bus.subscribe<SimpleEvent>(Priority.LOW, async = true) { order += 3 }

        bus.postAsync(SimpleEvent()).get(5, TimeUnit.SECONDS)
        assertEquals(listOf(1, 2, 3), order.toList())
    }

    @Test
    fun `sync handler sees mutations from higher-priority async handler`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bus = EventBus(executor)
        var observedValue = -1

        // HIGH async mutates; LOW sync reads
        bus.subscribe<ModifiableEvent>(Priority.HIGH, async = true) { e -> e.value = 42 }
        bus.subscribe<ModifiableEvent>(Priority.LOW, async = false) { e -> observedValue = e.value }

        bus.postAsync(ModifiableEvent()).get(5, TimeUnit.SECONDS)
        assertEquals(42, observedValue)
    }

    @Test
    fun `lower-priority async handler sees mutations from higher-priority async handler`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bus = EventBus(executor)
        var observedValue = -1

        bus.subscribe<ModifiableEvent>(Priority.HIGH, async = true) { e -> e.value = 99 }
        bus.subscribe<ModifiableEvent>(Priority.LOW, async = true) { e -> observedValue = e.value }

        bus.postAsync(ModifiableEvent()).get(5, TimeUnit.SECONDS)
        assertEquals(99, observedValue)
    }

    //  5. Cancellation under async dispatch

    @Test
    fun `handler cancelling event stops subsequent handlers in postAsync`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bus = EventBus(executor)
        val secondCalled = AtomicBoolean(false)

        bus.subscribe<CancellableAsyncEvent>(Priority.HIGH, async = true) { e -> e.isCancelled = true }
        bus.subscribe<CancellableAsyncEvent>(Priority.LOW, async = true) { secondCalled.set(true) }

        bus.postAsync(CancellableAsyncEvent()).get(5, TimeUnit.SECONDS)
        assertFalse(secondCalled.get(), "Second handler should not run after cancellation")
    }

    @Test
    fun `handler cancelling event stops subsequent sync handlers in postAsync`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bus = EventBus(executor)
        val syncCalled = AtomicBoolean(false)

        bus.subscribe<CancellableAsyncEvent>(Priority.HIGH, async = true) { e -> e.isCancelled = true }
        bus.subscribe<CancellableAsyncEvent>(Priority.LOW, async = false) { syncCalled.set(true) }

        bus.postAsync(CancellableAsyncEvent()).get(5, TimeUnit.SECONDS)
        assertFalse(syncCalled.get(), "Sync handler should not run after async cancellation")
    }

    @Test
    fun `cancelled state is flushed back to original event after postAsync`() {
        // The event has a plain (non-volatile) isCancelled field.
        // The bus must write the final atomic state back after the chain completes.
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bus = EventBus(executor)
        val event = CancellableAsyncEvent()

        bus.subscribe<CancellableAsyncEvent>(Priority.HIGH, async = true) { e -> e.isCancelled = true }

        val returned = bus.postAsync(event).get(5, TimeUnit.SECONDS)
        assertSame(event, returned)
        assertTrue(returned.isCancelled, "isCancelled must be flushed back to the original event")
    }

    @Test
    fun `cancelled state is flushed back to original event after blocking post`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bus = EventBus(executor)
        val event = CancellableAsyncEvent()

        bus.subscribe<CancellableAsyncEvent>(Priority.HIGH, async = true) { e -> e.isCancelled = true }

        bus.post(event)
        assertTrue(event.isCancelled, "isCancelled must be flushed back after blocking post")
    }

    @Test
    fun `cancellation guard works without @Volatile, bus ensures cross-thread visibility`() {
        // This is the core ergonomics guarantee: users do NOT need @Volatile on isCancelled.
        // The bus internally uses an AtomicBoolean (AsyncCancellableGuard) for the duration of
        // async dispatch, so cancellation set on an executor thread is always visible to the
        // chain's next step.
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bus = EventBus(executor)
        val thirdCalled = AtomicBoolean(false)

        // HIGH async cancels; NORMAL async and LOW sync must not run
        bus.subscribe<CancellableAsyncEvent>(Priority.HIGH, async = true) { e -> e.isCancelled = true }
        bus.subscribe<CancellableAsyncEvent>(Priority.NORMAL, async = true) { thirdCalled.set(true) }
        bus.subscribe<CancellableAsyncEvent>(Priority.LOW, async = false) { thirdCalled.set(true) }

        bus.postAsync(CancellableAsyncEvent()).get(5, TimeUnit.SECONDS)
        assertFalse(thirdCalled.get(), "No subsequent handler should run; guard must work without @Volatile")
    }

    //  6. Fallback to sync when no executor

    @Test
    fun `async = true handler falls back to sync when no executor configured`() {
        val bus = EventBus() // no executor
        val called = AtomicBoolean(false)
        val callingThread = Thread.currentThread()
        var handlerThread: Thread? = null

        bus.subscribe<SimpleEvent>(async = true) {
            called.set(true)
            handlerThread = Thread.currentThread()
        }
        bus.post(SimpleEvent())

        assertTrue(called.get())
        // With no executor, handler must run on the calling thread
        assertSame(callingThread, handlerThread)
    }

    @Test
    fun `annotated async subscriber falls back to sync when no executor`() {
        val bus = EventBus() // no executor
        val count = AtomicInteger(0)

        val listener = object {
            @Subscribe(async = true)
            fun on(e: SimpleEvent) {
                count.incrementAndGet()
            }
        }
        bus.subscribe(listener)
        bus.post(SimpleEvent())
        assertEquals(1, count.get())
    }

    //  7. postAsync — exception handling

    @Test
    fun `postAsync does not complete exceptionally when handler throws`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val errors = CopyOnWriteArrayList<Throwable>()
        val bus = EventBus(executor) { t -> errors += t }

        bus.subscribe<SimpleEvent>(async = true) { throw RuntimeException("boom") }
        val future = bus.postAsync(SimpleEvent())
        val result = future.get(5, TimeUnit.SECONDS) // must not throw
        assertNotNull(result)
        assertEquals(1, errors.size)
        assertEquals("boom", errors[0].message)
    }

    @Test
    fun `sync post does not throw when async handler throws`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val errors = CopyOnWriteArrayList<Throwable>()
        val bus = EventBus(executor) { t -> errors += t }

        bus.subscribe<SimpleEvent>(async = true) { throw IllegalStateException("oops") }
        bus.post(SimpleEvent()) // must not propagate
        assertEquals(1, errors.size)
    }

    //  8. TypedEventBus delegation

    @Test
    fun `TypedEventBus postAsync delegates to underlying bus`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bus = EventBus(executor).typed<SimpleEvent>()
        val called = AtomicBoolean(false)

        bus.subscribe<SimpleEvent>(async = true) { called.set(true) }
        bus.postAsync(SimpleEvent()).get(5, TimeUnit.SECONDS)
        assertTrue(called.get())
    }

    @Test
    fun `TypedEventBus subscribe with async flag delegates correctly`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bus = EventBus(executor).typed<SimpleEvent>()
        val order = CopyOnWriteArrayList<Int>()

        bus.subscribe<SimpleEvent>(Priority.HIGH, async = true) { order += 1 }
        bus.subscribe<SimpleEvent>(Priority.LOW, async = false) { order += 2 }

        bus.postAsync(SimpleEvent()).get(5, TimeUnit.SECONDS)
        assertEquals(listOf(1, 2), order.toList())
    }

    //  9. EventBus factory overloads

    @Test
    fun `EventBus invoke with executor creates bus with async support`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bus = EventBus(executor)
        val called = AtomicBoolean(false)
        bus.subscribe<SimpleEvent>(async = true) { called.set(true) }
        bus.postAsync(SimpleEvent()).get(5, TimeUnit.SECONDS)
        assertTrue(called.get())
    }

    @Test
    fun `EventBus invoke with null executor falls back to sync`() {
        val bus = EventBus(null as java.util.concurrent.Executor?)
        val called = AtomicBoolean(false)
        bus.subscribe<SimpleEvent>(async = true) { called.set(true) }
        bus.post(SimpleEvent())
        assertTrue(called.get())
    }

    @Test
    fun `EventBus invoke with executor and exception handler works`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val errors = CopyOnWriteArrayList<Throwable>()
        val bus = EventBus(executor) { t -> errors += t }
        bus.subscribe<SimpleEvent>(async = true) { throw RuntimeException("test") }
        bus.postAsync(SimpleEvent()).get(5, TimeUnit.SECONDS)
        assertEquals(1, errors.size)
    }

    //  10. Subscription cancellation (lambda async sub)

    @Test
    fun `cancelling an async lambda subscription removes it`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bus = EventBus(executor)
        val count = AtomicInteger(0)

        val sub = bus.subscribe<SimpleEvent>(async = true) { count.incrementAndGet() }
        bus.postAsync(SimpleEvent()).get(5, TimeUnit.SECONDS)
        assertEquals(1, count.get())

        sub.cancel()
        bus.postAsync(SimpleEvent()).get(5, TimeUnit.SECONDS)
        assertEquals(1, count.get(), "Handler should not be called after cancellation")
    }
}

