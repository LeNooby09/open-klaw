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
	 */
	fun checkAndRecord(key: String): Long {
		val now = System.currentTimeMillis()
		val entry = entries[key]

		if (entry != null && now < entry.lockedUntil) {
			return entry.lockedUntil - now
		}

		if (entry != null && (now - entry.firstAttemptAt) > config.rateLimitWindowMs) {
			entries.remove(key)
			return 0
		}

		val current = entries[key]
		if (current == null) {
			entries[key] = RateLimitEntry(attempts = 1, firstAttemptAt = now, lastAttemptAt = now)
			return 0
		}

		val newAttempts = current.attempts + 1
		if (newAttempts > config.rateLimitMaxAttempts) {
			val exponent = (newAttempts - config.rateLimitMaxAttempts).coerceAtMost(10)
			val delayMs = min(
				config.rateLimitBaseDelayMs * 2.0.pow(exponent).toLong(),
				300_000L
			)
			val lockedUntil = now + delayMs
			entries[key] = current.copy(attempts = newAttempts, lastAttemptAt = now, lockedUntil = lockedUntil)
			return delayMs
		}

		entries[key] = current.copy(attempts = newAttempts, lastAttemptAt = now)
		return 0
	}

	fun recordSuccess(key: String) {
		entries.remove(key)
	}

	fun cleanup() {
		val now = System.currentTimeMillis()
		entries.entries.removeIf { (now - it.value.lastAttemptAt) > config.rateLimitWindowMs * 2 }
	}
}
