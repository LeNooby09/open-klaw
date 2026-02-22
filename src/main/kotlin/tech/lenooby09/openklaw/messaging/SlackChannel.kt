package tech.lenooby09.openklaw.messaging

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.SlackConfig
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedDeque
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Slack messaging integration via the Slack Web API and Events API.
 *
 * Receives messages via Events API webhook callbacks and sends replies via
 * chat.postMessage. Requires a signing secret for webhook signature verification.
 */
class SlackChannel(
	private val config: SlackConfig,
	private val dedupMaxSize: Int = 10_000
) : MessageChannel {

	private val logger = LoggerFactory.getLogger(SlackChannel::class.java)
	private val json = Json { ignoreUnknownKeys = true; isLenient = true }
	private val httpClient = HttpClient(CIO) {
		engine {
			requestTimeout = 30_000
		}
	}

	override val channelType = ChannelType.SLACK
	override val displayName = "Slack"
	override var connected: Boolean = false
		private set

	private var messageHandler: (suspend (InboundMessage) -> Unit)? = null
	private var botUserId: String? = null

	/** LRU dedup deque — newest at tail, oldest at head. */
	private val processedEvents = ConcurrentLinkedDeque<String>()
	private val processedSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
	private val dedupLock = Any()

	override suspend fun start() {
		if (config.botToken.isEmpty()) {
			logger.warn("Slack bot token not configured, skipping Slack channel")
			return
		}

		if (config.signingSecret.isEmpty()) {
			logger.error("Slack signing secret not configured — refusing to start Slack channel (webhook would be unauthenticated)")
			return
		}

		// Validate token by calling auth.test
		try {
			val response = httpClient.post("${SLACK_API}/auth.test") {
				header("Authorization", "Bearer ${config.botToken}")
				contentType(ContentType.Application.Json)
				setBody("{}")
			}
			val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
			val ok = body["ok"]?.jsonPrimitive?.boolean ?: false
			if (ok) {
				botUserId = body["user_id"]?.jsonPrimitive?.content
				val botName = body["user"]?.jsonPrimitive?.content ?: "Unknown"
				connected = true
				logger.info("Slack bot connected as: $botName (ID: $botUserId)")
			} else {
				logger.error("Slack authentication failed: ${body["error"]?.jsonPrimitive?.content}")
				return
			}
		} catch (e: Exception) {
			logger.error("Failed to connect to Slack: ${e.message}", e)
			return
		}

		// Join configured channels
		for (channelId in config.channelIds) {
			try {
				val joinResponse = httpClient.post("${SLACK_API}/conversations.join") {
					header("Authorization", "Bearer ${config.botToken}")
					contentType(ContentType.Application.Json)
					setBody(buildJsonObject { put("channel", channelId) }.toString())
				}
				val joinBody = json.parseToJsonElement(joinResponse.bodyAsText()).jsonObject
				if (joinBody["ok"]?.jsonPrimitive?.boolean == true) {
					logger.info("Joined Slack channel: $channelId")
				}
			} catch (e: Exception) {
				logger.warn("Could not join Slack channel $channelId: ${e.message}")
			}
		}
	}

	override suspend fun stop() {
		connected = false
		httpClient.close()
		synchronized(dedupLock) {
			processedEvents.clear()
			processedSet.clear()
		}
		logger.info("Slack channel stopped")
	}

	override suspend fun sendMessage(message: OutboundMessage): Boolean {
		if (!connected) return false

		return try {
			val response = httpClient.post("${SLACK_API}/chat.postMessage") {
				header("Authorization", "Bearer ${config.botToken}")
				contentType(ContentType.Application.Json)
				setBody(buildJsonObject {
					put("channel", message.channelId)
					put("text", message.content)
					// Use mrkdwn formatting
					putJsonArray("blocks") {
						addJsonObject {
							put("type", "section")
							putJsonObject("text") {
								put("type", "mrkdwn")
								put("text", message.content)
							}
						}
					}
				}.toString())
			}
			val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
			val ok = body["ok"]?.jsonPrimitive?.boolean ?: false
			if (!ok) {
				logger.warn("Slack send failed: ${body["error"]?.jsonPrimitive?.content}")
			}
			ok
		} catch (e: Exception) {
			logger.error("Error sending Slack message: ${e.message}", e)
			false
		}
	}

	override fun onMessage(handler: suspend (InboundMessage) -> Unit) {
		messageHandler = handler
	}

	/**
	 * Adds a reaction emoji to a message.
	 */
	suspend fun addReaction(channelId: String, timestamp: String, emoji: String): Boolean {
		return try {
			val response = httpClient.post("${SLACK_API}/reactions.add") {
				header("Authorization", "Bearer ${config.botToken}")
				contentType(ContentType.Application.Json)
				setBody(buildJsonObject {
					put("channel", channelId)
					put("timestamp", timestamp)
					put("name", emoji)
				}.toString())
			}
			val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
			body["ok"]?.jsonPrimitive?.boolean == true
		} catch (e: Exception) {
			logger.error("Error adding Slack reaction: ${e.message}", e)
			false
		}
	}

	/**
	 * Pins a message in a channel.
	 */
	suspend fun pinMessage(channelId: String, timestamp: String): Boolean {
		return try {
			val response = httpClient.post("${SLACK_API}/pins.add") {
				header("Authorization", "Bearer ${config.botToken}")
				contentType(ContentType.Application.Json)
				setBody(buildJsonObject {
					put("channel", channelId)
					put("timestamp", timestamp)
				}.toString())
			}
			val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
			body["ok"]?.jsonPrimitive?.boolean == true
		} catch (e: Exception) {
			logger.error("Error pinning Slack message: ${e.message}", e)
			false
		}
	}

	/**
	 * Installs the Slack Events API webhook routes into a Ktor routing block.
	 * Signing secret verification is always enforced.
	 */
	fun installWebhookRoutes(routing: Routing) {
		routing.apply {
			post("/webhook/slack") {
				try {
					val bodyText = call.receiveText()

					// Always verify signing secret
					val timestamp = call.request.header("X-Slack-Request-Timestamp") ?: ""
					val signature = call.request.header("X-Slack-Signature") ?: ""
					if (!verifySlackSignature(bodyText, timestamp, signature)) {
						call.respond(HttpStatusCode.Unauthorized, "Invalid signature")
						return@post
					}

					val payload = json.parseToJsonElement(bodyText).jsonObject

					// Handle URL verification challenge
					val type = payload["type"]?.jsonPrimitive?.content
					if (type == "url_verification") {
						val challenge = payload["challenge"]?.jsonPrimitive?.content ?: ""
						call.respondText(challenge, ContentType.Text.Plain)
						return@post
					}

					// Handle event callbacks
					if (type == "event_callback") {
						handleEventPayload(payload)
					}

					call.respond(HttpStatusCode.OK)
				} catch (e: Exception) {
					logger.error("Error processing Slack webhook: ${e.message}", e)
					call.respond(HttpStatusCode.InternalServerError)
				}
			}
		}
	}

	/**
	 * Processes a Slack Events API event_callback payload.
	 */
	suspend fun handleEventPayload(payload: JsonObject) {
		val eventId = payload["event_id"]?.jsonPrimitive?.content ?: return

		// LRU deduplication
		if (!addToDedup(eventId)) return

		val event = payload["event"]?.jsonObject ?: return
		val eventType = event["type"]?.jsonPrimitive?.content ?: return

		when (eventType) {
			"message" -> processMessageEvent(event)
			"app_mention" -> processMessageEvent(event)
		}
	}

	private suspend fun processMessageEvent(event: JsonObject) {
		// Skip bot messages (including our own) unless configured otherwise
		val subtype = event["subtype"]?.jsonPrimitive?.content
		if (subtype != null && subtype != "file_share") return

		val userId = event["user"]?.jsonPrimitive?.content ?: return
		if (userId == botUserId) return

		val botId = event["bot_id"]?.jsonPrimitive?.content
		if (botId != null && !config.respondToOtherBots) return

		val text = event["text"]?.jsonPrimitive?.content ?: return
		val channelId = event["channel"]?.jsonPrimitive?.content ?: return
		val ts = event["ts"]?.jsonPrimitive?.content

		// Strip bot mention from text
		val cleanText = text
			.replace(Regex("<@${botUserId}>"), "")
			.trim()

		if (cleanText.isEmpty()) return

		// Add "eyes" reaction to acknowledge receipt
		if (ts != null) {
			addReaction(channelId, ts, "eyes")
		}

		// Look up user info for display name
		val displayName = lookupUserName(userId) ?: userId

		val inbound = InboundMessage(
			channelType = ChannelType.SLACK,
			channelId = channelId,
			senderId = userId,
			senderName = displayName,
			content = cleanText,
			metadata = buildMap {
				ts?.let { put("ts", it) }
				put("eventType", event["type"]?.jsonPrimitive?.content ?: "message")
			}
		)

		try {
			messageHandler?.invoke(inbound)
		} catch (e: Exception) {
			logger.error("Error handling Slack message: ${e.message}", e)
		}
	}

	private suspend fun lookupUserName(userId: String): String? {
		return try {
			val response = httpClient.get("${SLACK_API}/users.info") {
				header("Authorization", "Bearer ${config.botToken}")
				parameter("user", userId)
			}
			val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
			if (body["ok"]?.jsonPrimitive?.boolean == true) {
				val user = body["user"]?.jsonObject
				user?.get("real_name")?.jsonPrimitive?.content
					?: user?.get("name")?.jsonPrimitive?.content
			} else null
		} catch (e: Exception) {
			null
		}
	}

	/**
	 * Verifies Slack request signature using HMAC-SHA256.
	 * Uses constant-time comparison via MessageDigest.isEqual to prevent timing attacks.
	 */
	private fun verifySlackSignature(body: String, timestamp: String, signature: String): Boolean {
		return try {
			val sigBasestring = "v0:$timestamp:$body"
			val mac = Mac.getInstance("HmacSHA256")
			mac.init(SecretKeySpec(config.signingSecret.toByteArray(), "HmacSHA256"))
			val hash = mac.doFinal(sigBasestring.toByteArray())
			val computed = "v0=" + hash.joinToString("") { "%02x".format(it) }
			MessageDigest.isEqual(computed.toByteArray(), signature.toByteArray())
		} catch (e: Exception) {
			logger.error("Error verifying Slack signature: ${e.message}")
			false
		}
	}

	/**
	 * Adds an event ID to the LRU dedup set. Returns true if newly added, false if duplicate.
	 */
	private fun addToDedup(eventId: String): Boolean {
		synchronized(dedupLock) {
			if (!processedSet.add(eventId)) return false
			processedEvents.addLast(eventId)
			while (processedEvents.size > dedupMaxSize) {
				val evicted = processedEvents.pollFirst() ?: break
				processedSet.remove(evicted)
			}
			return true
		}
	}

	companion object {
		private const val SLACK_API = "https://slack.com/api"
	}
}
