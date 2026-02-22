package tech.lenooby09.openklaw.agent

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

/**
 * Configurable retry policy for failed LLM calls and tool executions.
 *
 * Supports exponential backoff with jitter, configurable max attempts,
 * and optional retry predicates to control which failures are retried.
 */
data class RetryPolicy(
	val maxAttempts: Int = 3,
	val initialDelayMs: Long = 500,
	val maxDelayMs: Long = 10_000,
	val backoffMultiplier: Double = 2.0,
	val jitterFraction: Double = 0.1
) {
	init {
		require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
		require(initialDelayMs >= 0) { "initialDelayMs must be >= 0" }
		require(maxDelayMs >= initialDelayMs) { "maxDelayMs must be >= initialDelayMs" }
		require(backoffMultiplier >= 1.0) { "backoffMultiplier must be >= 1.0" }
		require(jitterFraction in 0.0..1.0) { "jitterFraction must be in 0.0..1.0" }
	}

	companion object {
		private val logger = LoggerFactory.getLogger(RetryPolicy::class.java)

		/** No retries — execute once. */
		val NONE = RetryPolicy(maxAttempts = 1)

		/** Default policy for LLM calls. */
		val LLM_DEFAULT = RetryPolicy(maxAttempts = 3, initialDelayMs = 1000, maxDelayMs = 15_000)

		/** Default policy for tool executions. */
		val TOOL_DEFAULT = RetryPolicy(maxAttempts = 2, initialDelayMs = 500, maxDelayMs = 5_000)
	}

	/**
	 * Execute [block] with retry logic. On failure, retries up to [maxAttempts] - 1 times
	 * with exponential backoff.
	 *
	 * @param operationName Human-readable name for logging
	 * @param shouldRetry Optional predicate to decide if a specific exception should be retried.
	 *                    Returns true by default (retry all exceptions).
	 * @param block The suspending operation to execute
	 * @return The result of [block] if successful
	 * @throws Exception The last exception if all attempts fail
	 */
	suspend fun <T> execute(
		operationName: String,
		shouldRetry: (Exception) -> Boolean = { true },
		block: suspend (attempt: Int) -> T
	): T {
		var lastException: Exception? = null
		var currentDelay = initialDelayMs

		for (attempt in 1..maxAttempts) {
			try {
				return block(attempt)
			} catch (e: Exception) {
				lastException = e

				if (attempt == maxAttempts || !shouldRetry(e)) {
					logger.warn("$operationName failed on attempt $attempt/$maxAttempts (no more retries): ${e.message}")
					throw e
				}

				val jitter = (currentDelay * jitterFraction * (Math.random() * 2 - 1)).toLong()
				val delayWithJitter = (currentDelay + jitter).coerceIn(0, maxDelayMs)

				logger.info("$operationName failed on attempt $attempt/$maxAttempts, retrying in ${delayWithJitter}ms: ${e.message}")
				delay(delayWithJitter)

				currentDelay = (currentDelay * backoffMultiplier).toLong().coerceAtMost(maxDelayMs)
			}
		}

		throw lastException ?: IllegalStateException("Retry exhausted with no exception")
	}
}
