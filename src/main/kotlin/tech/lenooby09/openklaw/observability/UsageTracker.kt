package tech.lenooby09.openklaw.observability

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Logging & Observability — structured usage tracking and presence indicators.
 *
 * Tracks per-user and aggregate usage metrics: message counts, tool invocations,
 * LLM calls, and active presence. Thread-safe via atomic operations.
 */
class UsageTracker(
	private val maxTrackedUsers: Int = 10_000
) {
	private val logger = LoggerFactory.getLogger(UsageTracker::class.java)

	private val globalMetrics = UsageMetrics()
	private val perUserMetrics = ConcurrentHashMap<String, UsageMetrics>()

	// Presence tracking: username → last activity timestamp
	private val userPresence = ConcurrentHashMap<String, Long>()

	/**
	 * Record a chat message (user → agent).
	 */
	fun recordMessage(username: String) {
		globalMetrics.messageCount.incrementAndGet()
		metricsFor(username).messageCount.incrementAndGet()
		updatePresence(username)
		logger.debug("Usage: message from user=$username (total={})", globalMetrics.messageCount.get())
	}

	/**
	 * Record a tool invocation.
	 */
	fun recordToolCall(username: String, toolName: String) {
		globalMetrics.toolCallCount.incrementAndGet()
		metricsFor(username).toolCallCount.incrementAndGet()
		globalMetrics.toolUsage.merge(toolName, AtomicLong(1)) { existing, _ ->
			existing.also { it.incrementAndGet() }
		}
		logger.debug("Usage: tool=$toolName by user=$username")
	}

	/**
	 * Record an LLM API call.
	 */
	fun recordLlmCall(provider: String, model: String, durationMs: Long) {
		globalMetrics.llmCallCount.incrementAndGet()
		globalMetrics.llmTotalLatencyMs.addAndGet(durationMs)
		logger.debug("Usage: LLM call provider=$provider model=$model duration=${durationMs}ms")
	}

	/**
	 * Record an error.
	 */
	fun recordError(component: String, message: String) {
		globalMetrics.errorCount.incrementAndGet()
		logger.warn("Usage: error in component=$component: $message")
	}

	/**
	 * Update user presence (heartbeat / activity indicator).
	 */
	fun updatePresence(username: String) {
		userPresence[username] = System.currentTimeMillis()
	}

	/**
	 * Check if a user is currently "present" (active within the timeout window).
	 */
	fun isUserPresent(username: String, timeoutMs: Long = 300_000): Boolean {
		val lastSeen = userPresence[username] ?: return false
		return (System.currentTimeMillis() - lastSeen) < timeoutMs
	}

	/**
	 * Get list of currently present users.
	 */
	fun getPresentUsers(timeoutMs: Long = 300_000): List<PresenceInfo> {
		val now = System.currentTimeMillis()
		return userPresence.entries
			.filter { (now - it.value) < timeoutMs }
			.map { PresenceInfo(it.key, it.value, "online") }
			.sortedByDescending { it.lastSeenAt }
	}

	/**
	 * Get global usage statistics.
	 */
	fun getGlobalStats(): UsageStats {
		val llmCalls = globalMetrics.llmCallCount.get()
		val avgLatency = if (llmCalls > 0) globalMetrics.llmTotalLatencyMs.get() / llmCalls else 0
		return UsageStats(
			totalMessages = globalMetrics.messageCount.get(),
			totalToolCalls = globalMetrics.toolCallCount.get(),
			totalLlmCalls = llmCalls,
			totalErrors = globalMetrics.errorCount.get(),
			averageLlmLatencyMs = avgLatency,
			toolUsageBreakdown = globalMetrics.toolUsage.mapValues { it.value.get() },
			activeUsers = getPresentUsers().size
		)
	}

	/**
	 * Get per-user usage statistics.
	 */
	fun getUserStats(username: String): UserUsageStats? {
		val metrics = perUserMetrics[username] ?: return null
		return UserUsageStats(
			username = username,
			totalMessages = metrics.messageCount.get(),
			totalToolCalls = metrics.toolCallCount.get(),
			isPresent = isUserPresent(username),
			lastSeenAt = userPresence[username]
		)
	}

	/**
	 * Get all per-user stats.
	 */
	fun getAllUserStats(): List<UserUsageStats> {
		return perUserMetrics.keys.mapNotNull { getUserStats(it) }.sortedBy { it.username }
	}

	/**
	 * Clean up stale presence entries.
	 */
	fun cleanupStalePresence(timeoutMs: Long = 600_000) {
		val now = System.currentTimeMillis()
		val stale = userPresence.entries.filter { (now - it.value) > timeoutMs }.map { it.key }
		stale.forEach { userPresence.remove(it) }
		if (stale.isNotEmpty()) {
			logger.debug("Cleaned up {} stale presence entries", stale.size)
		}
	}

	private fun metricsFor(username: String): UsageMetrics {
		return perUserMetrics.computeIfAbsent(username) {
			// Evict least-recently-seen users when capacity is exceeded
			if (perUserMetrics.size >= maxTrackedUsers) {
				evictStalestUser()
			}
			UsageMetrics()
		}
	}

	/**
	 * Remove the user with the oldest presence timestamp to free capacity.
	 * Falls back to removing an arbitrary entry if no presence data exists.
	 */
	private fun evictStalestUser() {
		val stalest = perUserMetrics.keys
			.minByOrNull { userPresence[it] ?: 0L }
		if (stalest != null) {
			perUserMetrics.remove(stalest)
			userPresence.remove(stalest)
			logger.debug("Evicted stalest user metrics entry: $stalest (capacity: $maxTrackedUsers)")
		}
	}

	private class UsageMetrics {
		val messageCount = AtomicLong(0)
		val toolCallCount = AtomicLong(0)
		val llmCallCount = AtomicLong(0)
		val llmTotalLatencyMs = AtomicLong(0)
		val errorCount = AtomicLong(0)
		val toolUsage = ConcurrentHashMap<String, AtomicLong>()
	}
}

@Serializable
data class UsageStats(
	val totalMessages: Long,
	val totalToolCalls: Long,
	val totalLlmCalls: Long,
	val totalErrors: Long,
	val averageLlmLatencyMs: Long,
	val toolUsageBreakdown: Map<String, Long>,
	val activeUsers: Int
)

@Serializable
data class UserUsageStats(
	val username: String,
	val totalMessages: Long,
	val totalToolCalls: Long,
	val isPresent: Boolean,
	val lastSeenAt: Long?
)

@Serializable
data class PresenceInfo(
	val username: String,
	val lastSeenAt: Long,
	val status: String
)
