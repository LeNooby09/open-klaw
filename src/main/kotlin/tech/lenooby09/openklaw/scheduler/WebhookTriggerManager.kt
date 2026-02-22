package tech.lenooby09.openklaw.scheduler

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.SchedulerConfig
import tech.lenooby09.openklaw.security.RateLimiter
import tech.lenooby09.openklaw.config.SecurityConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

/**
 * Manages webhook endpoints that trigger agent workflows from external events.
 *
 * Each registered trigger gets a unique URL: `/api/webhooks/{triggerId}`
 * External services (GitHub, CI systems, monitoring tools) can POST to these
 * endpoints to invoke the agent with a configured task description.
 *
 * HMAC-SHA256 signature verification via `X-Webhook-Signature` header is
 * **mandatory** — webhook triggers refuse to start without a configured secret.
 */
class WebhookTriggerManager(
	private val config: SchedulerConfig,
	private val onWebhookTriggered: suspend (WebhookEvent) -> Unit
) {
	private val logger = LoggerFactory.getLogger(WebhookTriggerManager::class.java)
	private val triggers = ConcurrentHashMap<String, WebhookTrigger>()

	private val rateLimiter = RateLimiter(
		SecurityConfig(
			rateLimitMaxAttempts = config.webhookRateLimitMaxPerMinute,
			rateLimitWindowMs = 60_000L,
			rateLimitBaseDelayMs = 1000L
		)
	)

	/** Dedicated bounded executor for webhook task execution (prevents ForkJoinPool starvation). */
	private val taskExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
		1, config.maxConcurrentSchedulerTasks,
		60L, TimeUnit.SECONDS,
		LinkedBlockingQueue(config.maxConcurrentSchedulerTasks * 2)
	)

	fun addTrigger(trigger: WebhookTrigger): Boolean {
		if (!config.webhookTriggersEnabled) {
			logger.warn("Webhook triggers disabled, cannot add: ${trigger.name}")
			return false
		}
		if (triggers.size >= config.maxWebhookTriggers) {
			logger.warn("Max webhook triggers reached (${config.maxWebhookTriggers}), cannot add: ${trigger.name}")
			return false
		}
		if (!SAFE_ID_REGEX.matches(trigger.id)) {
			logger.warn("Invalid trigger ID format: ${trigger.id}")
			return false
		}
		triggers[trigger.id] = trigger
		logger.info("Webhook trigger added: ${trigger.name} → /api/webhooks/${trigger.id}")
		return true
	}

	fun removeTrigger(triggerId: String): Boolean {
		val removed = triggers.remove(triggerId)
		if (removed != null) {
			logger.info("Webhook trigger removed: ${removed.name}")
		}
		return removed != null
	}

	fun getTrigger(triggerId: String): WebhookTrigger? = triggers[triggerId]

	fun listTriggers(): List<WebhookTrigger> = triggers.values.toList()

	fun getTriggerCount(): Int = triggers.size

	fun cleanupRateLimiter() {
		rateLimiter.cleanup()
	}

	fun shutdown() {
		taskExecutor.shutdown()
		taskExecutor.awaitTermination(5, TimeUnit.SECONDS)
	}

	/**
	 * Installs webhook POST routes into the Ktor routing tree.
	 * GET /api/webhooks is NOT installed here — it is handled by GatewayServer behind auth.
	 *
	 * Webhook triggers **require** a configured secret. If no secret is set,
	 * routes are not installed and a warning is logged.
	 */
	fun installRoutes(routing: Routing, maxPayloadBytes: Long) {
		if (!config.webhookTriggersEnabled) return

		val secret = config.webhookSecret
		if (secret.isEmpty()) {
			logger.error("Webhook triggers enabled but no secret configured (set ${config.webhookSecretEnv} env var). Webhook routes NOT installed for security.")
			return
		}

		routing.post("/api/webhooks/{triggerId}") {
			val clientIp = call.request.local.remoteHost
			val waitMs = rateLimiter.checkAndRecord(clientIp)
			if (waitMs > 0) {
				call.response.header("Retry-After", ((waitMs / 1000) + 1).toString())
				call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Too many requests. Retry later."))
				return@post
			}

			val triggerId = call.parameters["triggerId"] ?: run {
				call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing trigger ID"))
				return@post
			}

			val trigger = triggers[triggerId]
			if (trigger == null) {
				call.respond(HttpStatusCode.NotFound, mapOf("error" to "Webhook trigger not found"))
				return@post
			}

			if (!trigger.enabled) {
				call.respond(HttpStatusCode.Gone, mapOf("error" to "Webhook trigger is disabled"))
				return@post
			}

			// Bounded body reading — hard limit regardless of Content-Length
			val body = try {
				val channel = call.receiveChannel()
				val limit = maxPayloadBytes.toInt() + 1
				val buffer = ByteArray(limit)
				var totalRead = 0
				while (totalRead < limit) {
					val read = channel.readAvailable(buffer, totalRead, limit - totalRead)
					if (read == -1) break
					totalRead += read
				}
				if (totalRead > maxPayloadBytes) {
					call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "Payload too large"))
					return@post
				}
				buffer.decodeToString(0, totalRead)
			} catch (e: Exception) {
				call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Failed to read request body"))
				return@post
			}

			// Mandatory HMAC-SHA256 signature verification
			val signature = call.request.header("X-Webhook-Signature") ?: ""
			if (!verifySignature(body, signature, secret)) {
				logger.warn("Webhook signature verification failed for trigger: $triggerId")
				call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid signature"))
				return@post
			}

			fireWebhook(trigger, body)
			call.respond(HttpStatusCode.OK, mapOf("status" to "accepted", "triggerId" to triggerId))
		}

		logger.info("Webhook trigger routes installed — ${triggers.size} triggers (signature verification mandatory)")
	}

	private fun fireWebhook(trigger: WebhookTrigger, body: String) {
		val event = WebhookEvent(
			triggerId = trigger.id,
			triggerName = trigger.name,
			taskDescription = trigger.taskDescription,
			payload = body,
			username = trigger.username,
			createdByAdmin = trigger.createdByAdmin,
			timestamp = System.currentTimeMillis()
		)
		try {
			taskExecutor.submit {
				kotlinx.coroutines.runBlocking { onWebhookTriggered(event) }
			}
		} catch (e: java.util.concurrent.RejectedExecutionException) {
			logger.warn("Webhook task rejected (executor full) for trigger: ${trigger.id}")
		}
		logger.info("Webhook triggered: ${trigger.name} (${trigger.id})")
	}

	companion object {
		private val SAFE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")

		fun verifySignature(payload: String, signature: String, secret: String): Boolean {
			if (signature.isBlank()) return false
			return try {
				val mac = Mac.getInstance("HmacSHA256")
				mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
				val expected = mac.doFinal(payload.toByteArray())
					.joinToString("") { "%02x".format(it) }
				val providedHex = signature.removePrefix("sha256=")
				MessageDigest.isEqual(expected.toByteArray(), providedHex.toByteArray())
			} catch (e: Exception) {
				false
			}
		}
	}
}

@Serializable
data class WebhookTrigger(
	val id: String,
	val name: String,
	val taskDescription: String,
	val username: String = "system",
	val enabled: Boolean = true,
	val createdByAdmin: Boolean = false,
	val createdAt: Long = System.currentTimeMillis()
)

data class WebhookEvent(
	val triggerId: String,
	val triggerName: String,
	val taskDescription: String,
	val payload: String,
	val username: String,
	val createdByAdmin: Boolean = false,
	val timestamp: Long
)
