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
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.WhatsAppConfig
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedDeque
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * WhatsApp messaging integration via the WhatsApp Cloud API (Meta Business Platform).
 *
 * Receives messages via webhook callbacks (with X-Hub-Signature-256 verification)
 * and sends replies via the Cloud API. Supports text messages and interactive buttons.
 */
class WhatsAppChannel(
	private val config: WhatsAppConfig,
	private val dedupMaxSize: Int = 10_000
) : MessageChannel {

	private val logger = LoggerFactory.getLogger(WhatsAppChannel::class.java)
	private val json = Json { ignoreUnknownKeys = true; isLenient = true }
	private val httpClient = HttpClient(CIO) {
		engine {
			requestTimeout = 30_000
		}
	}

	override val channelType = ChannelType.WHATSAPP
	override val displayName = "WhatsApp"
	override var connected: Boolean = false
		private set

	private var messageHandler: (suspend (InboundMessage) -> Unit)? = null

	/** LRU dedup deque — newest at tail, oldest at head. */
	private val processedMessages = ConcurrentLinkedDeque<String>()
	private val processedSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
	private val dedupLock = Any()

	override suspend fun start() {
		if (config.accessToken.isEmpty() || config.phoneNumberId.isEmpty()) {
			logger.warn("WhatsApp access token or phone number ID not configured, skipping WhatsApp channel")
			return
		}

		if (config.appSecret.isEmpty()) {
			logger.warn("WhatsApp app secret not configured — webhook signature verification will be unavailable")
		}

		connected = true
		logger.info("WhatsApp channel initialized (phoneNumberId=${config.phoneNumberId})")
	}

	override suspend fun stop() {
		connected = false
		httpClient.close()
		synchronized(dedupLock) {
			processedMessages.clear()
			processedSet.clear()
		}
		logger.info("WhatsApp channel stopped")
	}

	override suspend fun sendMessage(message: OutboundMessage): Boolean {
		if (!connected) return false

		return try {
			val response = httpClient.post("${WHATSAPP_API}/${config.phoneNumberId}/messages") {
				header("Authorization", "Bearer ${config.accessToken}")
				contentType(ContentType.Application.Json)
				setBody(buildJsonObject {
					put("messaging_product", "whatsapp")
					put("to", message.recipientId)
					put("type", "text")
					putJsonObject("text") {
						put("body", message.content)
					}
				}.toString())
			}
			val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
			val hasMessages = body["messages"]?.jsonArray?.isNotEmpty() == true
			if (!hasMessages) {
				logger.warn("WhatsApp send may have failed: ${response.bodyAsText()}")
			}
			hasMessages
		} catch (e: Exception) {
			logger.error("Error sending WhatsApp message: ${e.message}", e)
			false
		}
	}

	override fun onMessage(handler: suspend (InboundMessage) -> Unit) {
		messageHandler = handler
	}

	/**
	 * Installs the WhatsApp webhook routes into a Ktor routing block.
	 */
	fun installWebhookRoutes(routing: Routing) {
		routing.apply {
			// Webhook verification (GET)
			get("/webhook/whatsapp") {
				val mode = call.parameters["hub.mode"]
				val token = call.parameters["hub.verify_token"]
				val challenge = call.parameters["hub.challenge"]

				if (mode == "subscribe" && token == config.webhookVerifyToken) {
					logger.info("WhatsApp webhook verified")
					call.respondText(challenge ?: "", ContentType.Text.Plain)
				} else {
					call.respond(HttpStatusCode.Forbidden, "Verification failed")
				}
			}

			// Webhook notification (POST) — with signature verification
			post("/webhook/whatsapp") {
				try {
					val bodyText = call.receiveText()

					// Verify X-Hub-Signature-256 if app secret is configured
					if (config.appSecret.isNotEmpty()) {
						val signature = call.request.header("X-Hub-Signature-256") ?: ""
						if (!verifyWebhookSignature(bodyText, signature)) {
							logger.warn("WhatsApp webhook signature verification failed")
							call.respond(HttpStatusCode.Unauthorized, "Invalid signature")
							return@post
						}
					}

					handleWebhookPayload(bodyText)
					call.respond(HttpStatusCode.OK)
				} catch (e: Exception) {
					logger.error("Error processing WhatsApp webhook: ${e.message}", e)
					call.respond(HttpStatusCode.InternalServerError)
				}
			}
		}
	}

	/**
	 * Verifies the X-Hub-Signature-256 header using HMAC-SHA256 with the app secret.
	 * Uses constant-time comparison to prevent timing attacks.
	 */
	private fun verifyWebhookSignature(body: String, signatureHeader: String): Boolean {
		return try {
			if (!signatureHeader.startsWith("sha256=")) return false
			val expectedSignature = signatureHeader.removePrefix("sha256=")
			val mac = Mac.getInstance("HmacSHA256")
			mac.init(SecretKeySpec(config.appSecret.toByteArray(), "HmacSHA256"))
			val hash = mac.doFinal(body.toByteArray())
			val computed = hash.joinToString("") { "%02x".format(it) }
			MessageDigest.isEqual(computed.toByteArray(), expectedSignature.toByteArray())
		} catch (e: Exception) {
			logger.error("Error verifying WhatsApp signature: ${e.message}")
			false
		}
	}

	/**
	 * Processes an incoming WhatsApp Cloud API webhook payload.
	 */
	suspend fun handleWebhookPayload(bodyText: String) {
		val payload = json.parseToJsonElement(bodyText).jsonObject
		val entries = payload["entry"]?.jsonArray ?: return

		for (entry in entries) {
			val changes = entry.jsonObject["changes"]?.jsonArray ?: continue
			for (change in changes) {
				val value = change.jsonObject["value"]?.jsonObject ?: continue
				val messages = value["messages"]?.jsonArray ?: continue
				val contacts = value["contacts"]?.jsonArray

				for (msg in messages) {
					val msgObj = msg.jsonObject
					val msgId = msgObj["id"]?.jsonPrimitive?.content ?: continue
					val from = msgObj["from"]?.jsonPrimitive?.content ?: continue
					val type = msgObj["type"]?.jsonPrimitive?.content ?: continue

					// LRU deduplication
					if (!addToDedup(msgId)) continue

					val text = when (type) {
						"text" -> msgObj["text"]?.jsonObject?.get("body")?.jsonPrimitive?.content
						"interactive" -> {
							val interactive = msgObj["interactive"]?.jsonObject
							interactive?.get("button_reply")?.jsonObject?.get("title")?.jsonPrimitive?.content
								?: interactive?.get("list_reply")?.jsonObject?.get("title")?.jsonPrimitive?.content
						}
						else -> null
					} ?: continue

					// Look up contact name
					val contactName = contacts?.firstOrNull {
						it.jsonObject["wa_id"]?.jsonPrimitive?.content == from
					}?.jsonObject?.get("profile")?.jsonObject?.get("name")?.jsonPrimitive?.content ?: from

					// Mark as read
					markAsRead(msgId)

					val inbound = InboundMessage(
						channelType = ChannelType.WHATSAPP,
						channelId = from,
						senderId = from,
						senderName = contactName,
						content = text,
						metadata = mapOf("messageId" to msgId, "type" to type)
					)

					try {
						messageHandler?.invoke(inbound)
					} catch (e: Exception) {
						logger.error("Error handling WhatsApp message: ${e.message}", e)
					}
				}
			}
		}
	}

	/**
	 * Adds a message ID to the LRU dedup set. Returns true if newly added, false if duplicate.
	 */
	private fun addToDedup(messageId: String): Boolean {
		synchronized(dedupLock) {
			if (!processedSet.add(messageId)) return false
			processedMessages.addLast(messageId)
			while (processedMessages.size > dedupMaxSize) {
				val evicted = processedMessages.pollFirst() ?: break
				processedSet.remove(evicted)
			}
			return true
		}
	}

	private suspend fun markAsRead(messageId: String) {
		try {
			httpClient.post("${WHATSAPP_API}/${config.phoneNumberId}/messages") {
				header("Authorization", "Bearer ${config.accessToken}")
				contentType(ContentType.Application.Json)
				setBody(buildJsonObject {
					put("messaging_product", "whatsapp")
					put("status", "read")
					put("message_id", messageId)
				}.toString())
			}
		} catch (e: Exception) {
			logger.debug("Failed to mark WhatsApp message as read: ${e.message}")
		}
	}

	companion object {
		private const val WHATSAPP_API = "https://graph.facebook.com/v21.0"
	}
}
