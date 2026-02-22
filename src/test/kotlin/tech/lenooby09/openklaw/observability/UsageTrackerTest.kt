package tech.lenooby09.openklaw.observability

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UsageTrackerTest {

	@Test
	fun `recordMessage increments counts`() {
		val tracker = UsageTracker()
		tracker.recordMessage("alice")
		tracker.recordMessage("alice")
		tracker.recordMessage("bob")

		val stats = tracker.getGlobalStats()
		assertEquals(3, stats.totalMessages)

		val aliceStats = tracker.getUserStats("alice")
		assertNotNull(aliceStats)
		assertEquals(2, aliceStats!!.totalMessages)

		val bobStats = tracker.getUserStats("bob")
		assertNotNull(bobStats)
		assertEquals(1, bobStats!!.totalMessages)
	}

	@Test
	fun `recordToolCall tracks per-tool usage`() {
		val tracker = UsageTracker()
		tracker.recordToolCall("alice", "shell")
		tracker.recordToolCall("alice", "shell")
		tracker.recordToolCall("alice", "browser")

		val stats = tracker.getGlobalStats()
		assertEquals(3, stats.totalToolCalls)
		assertEquals(2, stats.toolUsageBreakdown["shell"])
		assertEquals(1, stats.toolUsageBreakdown["browser"])
	}

	@Test
	fun `recordLlmCall tracks latency`() {
		val tracker = UsageTracker()
		tracker.recordLlmCall("openai", "gpt-4", 100)
		tracker.recordLlmCall("openai", "gpt-4", 200)

		val stats = tracker.getGlobalStats()
		assertEquals(2, stats.totalLlmCalls)
		assertEquals(150, stats.averageLlmLatencyMs)
	}

	@Test
	fun `recordError increments error count`() {
		val tracker = UsageTracker()
		tracker.recordError("llm", "timeout")
		tracker.recordError("tool", "crash")

		assertEquals(2, tracker.getGlobalStats().totalErrors)
	}

	@Test
	fun `presence tracking works`() {
		val tracker = UsageTracker()
		tracker.updatePresence("alice")

		assertTrue(tracker.isUserPresent("alice"))
		assertFalse(tracker.isUserPresent("bob"))

		val present = tracker.getPresentUsers()
		assertEquals(1, present.size)
		assertEquals("alice", present[0].username)
		assertEquals("online", present[0].status)
	}

	@Test
	fun `presence expires after timeout`() {
		val tracker = UsageTracker()
		tracker.updatePresence("alice")

		// With a 0ms timeout, everyone is expired
		assertFalse(tracker.isUserPresent("alice", timeoutMs = 0))
		assertTrue(tracker.getPresentUsers(timeoutMs = 0).isEmpty())
	}

	@Test
	fun `getUserStats returns null for unknown user`() {
		val tracker = UsageTracker()
		assertNull(tracker.getUserStats("nonexistent"))
	}

	@Test
	fun `getAllUserStats returns sorted list`() {
		val tracker = UsageTracker()
		tracker.recordMessage("charlie")
		tracker.recordMessage("alice")

		val all = tracker.getAllUserStats()
		assertEquals(2, all.size)
		assertEquals("alice", all[0].username)
		assertEquals("charlie", all[1].username)
	}

	@Test
	fun `cleanupStalePresence removes old entries`() {
		val tracker = UsageTracker()
		tracker.updatePresence("alice")

		Thread.sleep(50)
		// With timeout=1ms, all entries are stale after the sleep
		tracker.cleanupStalePresence(timeoutMs = 1)
		assertFalse(tracker.isUserPresent("alice", timeoutMs = Long.MAX_VALUE))
	}

	@Test
	fun `global stats with no data returns zeros`() {
		val tracker = UsageTracker()
		val stats = tracker.getGlobalStats()
		assertEquals(0, stats.totalMessages)
		assertEquals(0, stats.totalToolCalls)
		assertEquals(0, stats.totalLlmCalls)
		assertEquals(0, stats.totalErrors)
		assertEquals(0, stats.averageLlmLatencyMs)
		assertTrue(stats.toolUsageBreakdown.isEmpty())
		assertEquals(0, stats.activeUsers)
	}
}
