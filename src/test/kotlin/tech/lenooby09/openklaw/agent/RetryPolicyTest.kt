package tech.lenooby09.openklaw.agent

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class RetryPolicyTest {

	@Test
	fun `succeeds on first attempt`() = runBlocking {
		val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 10)
		val counter = AtomicInteger(0)

		val result = policy.execute("test") {
			counter.incrementAndGet()
			"success"
		}

		assertEquals("success", result)
		assertEquals(1, counter.get())
	}

	@Test
	fun `retries on failure then succeeds`() = runBlocking {
		val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 10, maxDelayMs = 50)
		val counter = AtomicInteger(0)

		val result = policy.execute("test") {
			val attempt = counter.incrementAndGet()
			if (attempt < 3) throw RuntimeException("fail $attempt")
			"success on $attempt"
		}

		assertEquals("success on 3", result)
		assertEquals(3, counter.get())
	}

	@Test
	fun `throws after max attempts exhausted`() = runBlocking {
		val policy = RetryPolicy(maxAttempts = 2, initialDelayMs = 10, maxDelayMs = 50)
		val counter = AtomicInteger(0)

		val ex = assertThrows(RuntimeException::class.java) {
			runBlocking {
				policy.execute("test") {
					counter.incrementAndGet()
					throw RuntimeException("always fails")
				}
			}
		}

		assertEquals("always fails", ex.message)
		assertEquals(2, counter.get())
	}

	@Test
	fun `shouldRetry predicate can prevent retries`() = runBlocking {
		val policy = RetryPolicy(maxAttempts = 5, initialDelayMs = 10, maxDelayMs = 50)
		val counter = AtomicInteger(0)

		val ex = assertThrows(java.io.IOException::class.java) {
			runBlocking {
				policy.execute("test", shouldRetry = { it is RuntimeException }) {
					counter.incrementAndGet()
					throw java.io.IOException("not retryable")
				}
			}
		}

		assertEquals("not retryable", ex.message)
		assertEquals(1, counter.get()) // Only one attempt — predicate said no retry for IOException
	}

	@Test
	fun `NONE policy executes once`() = runBlocking {
		val counter = AtomicInteger(0)

		val ex = assertThrows(RuntimeException::class.java) {
			runBlocking {
				RetryPolicy.NONE.execute("test") {
					counter.incrementAndGet()
					throw RuntimeException("single attempt")
				}
			}
		}

		assertEquals(1, counter.get())
		assertEquals("single attempt", ex.message)
	}

	@Test
	fun `validation rejects invalid parameters`() {
		assertThrows(IllegalArgumentException::class.java) {
			RetryPolicy(maxAttempts = 0)
		}
		assertThrows(IllegalArgumentException::class.java) {
			RetryPolicy(initialDelayMs = -1)
		}
		assertThrows(IllegalArgumentException::class.java) {
			RetryPolicy(initialDelayMs = 100, maxDelayMs = 50)
		}
		assertThrows(IllegalArgumentException::class.java) {
			RetryPolicy(backoffMultiplier = 0.5)
		}
		assertThrows(IllegalArgumentException::class.java) {
			RetryPolicy(jitterFraction = 1.5)
		}
	}

	@Test
	fun `LLM_DEFAULT has sensible values`() {
		val policy = RetryPolicy.LLM_DEFAULT
		assertEquals(3, policy.maxAttempts)
		assertTrue(policy.initialDelayMs > 0)
	}

	@Test
	fun `TOOL_DEFAULT has sensible values`() {
		val policy = RetryPolicy.TOOL_DEFAULT
		assertEquals(2, policy.maxAttempts)
		assertTrue(policy.initialDelayMs > 0)
	}
}
