package tech.lenooby09.openklaw.security

import tech.lenooby09.openklaw.config.SecurityConfig
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow

data class RateLimitEntry(
	val attempts: Int = 0,
	val firstAttemptAt: Long = System.currentTimeMillis(),
	val lastAttemptAt: Long = System.currentTimeMillis(),
	val lockedUntil: Long = 0
)

class RateLimiter(private val config: SecurityConfig) {
	private val entries = ConcurrentHashMap<String, RateLimitEntry>()

	/**
	 * Checks whether the given key (e.g. IP or username) is currently rate-limited.
	 * Returns the number of milliseconds the caller must wait, or 0 if the request is allowed.
	 * Uses atomic compute() to prevent race conditions.
	 */
	fun checkAndRecord(key: String): Long {
		var waitMs = 0L
		entries.compute(key) { _, existing ->
			val now = System.currentTimeMillis()

			if (existing == null) {
				waitMs = 0
				return@compute RateLimitEntry(attempts = 1, firstAttemptAt = now, lastAttemptAt = now)
			}

			// Currently locked out
			if (now < existing.lockedUntil) {
				waitMs = existing.lockedUntil - now
				return@compute existing
			}

			// Window expired — reset
			if ((now - existing.firstAttemptAt) > config.rateLimitWindowMs) {
				waitMs = 0
				return@compute RateLimitEntry(attempts = 1, firstAttemptAt = now, lastAttemptAt = now)
			}

			val newAttempts = existing.attempts + 1
			if (newAttempts > config.rateLimitMaxAttempts) {
				val exponent = (newAttempts - config.rateLimitMaxAttempts).coerceAtMost(10)
				val delayMs = min(
					config.rateLimitBaseDelayMs * 2.0.pow(exponent).toLong(),
					300_000L
				)
				waitMs = delayMs
				return@compute existing.copy(attempts = newAttempts, lastAttemptAt = now, lockedUntil = now + delayMs)
			}

			waitMs = 0
			existing.copy(attempts = newAttempts, lastAttemptAt = now)
		}
		return waitMs
	}

	fun recordSuccess(key: String) {
		entries.remove(key)
	}

	fun cleanup() {
		val now = System.currentTimeMillis()
		entries.entries.removeIf { (now - it.value.lastAttemptAt) > config.rateLimitWindowMs * 2 }
	}
}
