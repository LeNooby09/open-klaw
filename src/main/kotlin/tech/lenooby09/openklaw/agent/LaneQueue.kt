package tech.lenooby09.openklaw.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Lane Queue System — ensures serial task execution per session.
 *
 * Each session gets its own "lane" (a Mutex). Concurrent requests for the same session
 * are queued and executed one at a time, preventing race conditions and state drift.
 * Different sessions execute independently in parallel.
 */
class LaneQueue {
	private val logger = LoggerFactory.getLogger(LaneQueue::class.java)
	private val lanes = ConcurrentHashMap<String, Mutex>()

	/**
	 * Execute [block] serially within the lane identified by [sessionId].
	 * If another coroutine is already executing in this lane, this call suspends until
	 * the lane is free.
	 */
	suspend fun <T> withLane(sessionId: String, block: suspend () -> T): T {
		val mutex = lanes.computeIfAbsent(sessionId) { Mutex() }
		logger.debug("Acquiring lane for session={}", sessionId)
		return mutex.withLock {
			logger.debug("Entered lane for session={}", sessionId)
			try {
				block()
			} finally {
				logger.debug("Released lane for session={}", sessionId)
			}
		}
	}

	/**
	 * Check if a session lane is currently occupied (task in progress).
	 */
	fun isLaneOccupied(sessionId: String): Boolean {
		return lanes[sessionId]?.isLocked == true
	}

	/**
	 * Remove the lane for a session that is no longer active.
	 * Should be called during session cleanup to prevent memory leaks.
	 */
	fun removeLane(sessionId: String) {
		// Only remove the lane if its mutex is not currently locked.
		// Removing a locked mutex would allow a new mutex to be created for the same session,
		// breaking the serialization guarantee.
		lanes.computeIfPresent(sessionId) { _, mutex ->
			if (mutex.isLocked) {
				logger.debug("Lane for session={} is still locked — deferring removal", sessionId)
				mutex // keep it
			} else {
				logger.debug("Removed lane for session={}", sessionId)
				null // remove
			}
		}
	}

	/**
	 * Get the number of active lanes.
	 */
	fun getLaneCount(): Int = lanes.size

	/**
	 * Get the number of currently occupied (locked) lanes.
	 */
	fun getOccupiedLaneCount(): Int = lanes.values.count { it.isLocked }

	/**
	 * Clean up lanes for sessions that are no longer tracked.
	 * @param activeSessions set of session IDs that are still active
	 */
	fun cleanupStaleLanes(activeSessions: Set<String>) {
		val stale = lanes.keys().toList().filter { it !in activeSessions }
		var removed = 0
		for (id in stale) {
			lanes.computeIfPresent(id) { _, mutex ->
				if (mutex.isLocked) mutex else { removed++; null }
			}
		}
		if (removed > 0) {
			logger.info("Cleaned up {} stale lanes", removed)
		}
	}
}
