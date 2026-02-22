package tech.lenooby09.openklaw.agent

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class LaneQueueTest {

	@Test
	fun `serial execution within same session`() = runBlocking {
		val queue = LaneQueue()
		val order = mutableListOf<Int>()

		// Launch two tasks for the same session — they must execute serially
		val job1 = launch {
			queue.withLane("session-1") {
				order.add(1)
				delay(100)
				order.add(2)
			}
		}
		delay(10) // Ensure job1 starts first
		val job2 = launch {
			queue.withLane("session-1") {
				order.add(3)
				delay(50)
				order.add(4)
			}
		}

		job1.join()
		job2.join()

		assertEquals(listOf(1, 2, 3, 4), order, "Tasks in same session must execute serially")
	}

	@Test
	fun `parallel execution across different sessions`() = runBlocking {
		val queue = LaneQueue()
		val counter = AtomicInteger(0)

		val job1 = launch {
			queue.withLane("session-A") {
				counter.incrementAndGet()
				delay(200)
			}
		}
		val job2 = launch {
			queue.withLane("session-B") {
				counter.incrementAndGet()
				delay(200)
			}
		}

		delay(50) // Let both start
		// Both should be running concurrently
		assertEquals(2, counter.get(), "Different sessions should run in parallel")

		job1.join()
		job2.join()
	}

	@Test
	fun `isLaneOccupied returns correct state`() = runBlocking {
		val queue = LaneQueue()

		assertFalse(queue.isLaneOccupied("session-1"))

		val job = launch {
			queue.withLane("session-1") {
				delay(200)
			}
		}
		delay(50)
		assertTrue(queue.isLaneOccupied("session-1"))

		job.join()
		assertFalse(queue.isLaneOccupied("session-1"))
	}

	@Test
	fun `removeLane cleans up`() {
		val queue = LaneQueue()
		runBlocking {
			queue.withLane("session-1") { /* no-op */ }
		}
		assertEquals(1, queue.getLaneCount())
		queue.removeLane("session-1")
		assertEquals(0, queue.getLaneCount())
	}

	@Test
	fun `cleanupStaleLanes removes inactive lanes`() {
		val queue = LaneQueue()
		runBlocking {
			queue.withLane("active") { /* no-op */ }
			queue.withLane("stale") { /* no-op */ }
		}
		assertEquals(2, queue.getLaneCount())
		queue.cleanupStaleLanes(setOf("active"))
		assertEquals(1, queue.getLaneCount())
	}

	@Test
	fun `withLane propagates exceptions`() = runBlocking {
		val queue = LaneQueue()
		val ex = assertThrows(RuntimeException::class.java) {
			runBlocking {
				queue.withLane("session-1") {
					throw RuntimeException("boom")
				}
			}
		}
		assertEquals("boom", ex.message)
	}

	@Test
	fun `withLane returns value`() = runBlocking {
		val queue = LaneQueue()
		val result = queue.withLane("session-1") { 42 }
		assertEquals(42, result)
	}
}
