package tech.lenooby09.openklaw.messaging

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.TelegramConfig

/**
 * Telegram messaging integration via the Telegram Bot API.
 *
 * Uses long polling (getUpdates) to receive messages and sendMessage to reply.
 * Supports inline buttons, reactions (via setMessageReaction), and streaming
 * (progressive message editing via editMessageText for long responses).
 */
class TelegramChannel(
	private val config: TelegramConfig
) : MessageChannel {

	private val logger = LoggerFactory.getLogger(TelegramChannel::class.java)
	private val json = Json { ignoreUnknownKeys = true; isLenient = true }
	private val httpClient = HttpClient(CIO) {
		engine {
			requestTimeout = config.longPollTimeoutSeconds * 1000L + 10_000
		}
	}

	override val channelType = ChannelType.TELEGRAM
	override val displayName = "Telegram"
	override var connected: Boolean = false
		private set

	private var messageHandler: (suspend (InboundMessage) -> Unit)? = null
	private var pollingJob: Job? = null
	private var lastUpdateId: Long = 0
	private var botUsername: String? = null

	private val apiBase get() = "${TELEGRAM_API}/bot${config.botToken}"

	override suspend fun start() {
		if (config.botToken.isEmpty()) {
			logger.warn("Telegram bot token not configured, skipping Telegram channel")
			return
		}

		// Validate token by fetching bot info
		try {
			val response = httpClient.get("$apiBase/getMe")
			val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
			val ok = body["ok"]?.jsonPrimitive?.boolean ?: false
			if (ok) {
				val result = body["result"]?.jsonObject
				botUsername = result?.get("username")?.jsonPrimitive?.content
				connected = true
				logger.info("Telegram bot connected as: @$botUsername")
			} else {
				logger.error("Telegram authentication failed: ${body["description"]?.jsonPrimitive?.content}")
				return
			}
		} catch (e: Exception) {
			logger.error("Failed to connect to Telegram: ${e.message}", e)
			return
		}

		// Start long polling
		pollingJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
			pollUpdates()
		}
	}

	override suspend fun stop() {
		pollingJob?.cancel()
		pollingJob = null
		connected = false
		httpClient.close()
		logger.info("Telegram channel stopped")
	}

	override suspend fun sendMessage(message: OutboundMessage): Boolean {
		if (!connected) return false

		return try {
			// Split long messages (Telegram has 4096 char limit)
			val chunks = splitMessage(message.content, TELEGRAM_MAX_MESSAGE_LENGTH)
			for (chunk in chunks) {
				val response = httpClient.post("$apiBase/sendMessage") {
					contentType(ContentType.Application.Json)
					setBody(buildJsonObject {
						put("chat_id", message.channelId)
						put("text", chunk)
						put("parse_mode", "Markdown")
					}.toString())
				}
				val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
				if (body["ok"]?.jsonPrimitive?.boolean != true) {
					// Retry without Markdown parse mode if formatting fails
					val retryResponse = httpClient.post("$apiBase/sendMessage") {
						contentType(ContentType.Application.Json)
						setBody(buildJsonObject {
							put("chat_id", message.channelId)
							put("text", chunk)
						}.toString())
					}
					val retryBody = json.parseToJsonElement(retryResponse.bodyAsText()).jsonObject
					if (retryBody["ok"]?.jsonPrimitive?.boolean != true) {
						logger.warn("Telegram send failed: ${retryBody["description"]?.jsonPrimitive?.content}")
						return false
					}
				}
			}
			true
		} catch (e: Exception) {
			logger.error("Error sending Telegram message: ${e.message}", e)
			false
		}
	}

	override fun onMessage(handler: suspend (InboundMessage) -> Unit) {
		messageHandler = handler
	}

	/**
	 * Sends a reaction emoji on a specific message.
	 */
	suspend fun setReaction(chatId: String, messageId: Long, emoji: String): Boolean {
		return try {
			val response = httpClient.post("$apiBase/setMessageReaction") {
				contentType(ContentType.Application.Json)
				setBody(buildJsonObject {
					put("chat_id", chatId)
					put("message_id", messageId)
					putJsonArray("reaction") {
						addJsonObject {
							put("type", "emoji")
							put("emoji", emoji)
						}
					}
				}.toString())
			}
			val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
			body["ok"]?.jsonPrimitive?.boolean == true
		} catch (e: Exception) {
			logger.error("Error setting Telegram reaction: ${e.message}", e)
			false
		}
	}

	/**
	 * Sends a message with inline keyboard buttons.
	 */
	suspend fun sendWithButtons(
		chatId: String,
		text: String,
		buttons: List<List<Pair<String, String>>>
	): Boolean {
		return try {
			val response = httpClient.post("$apiBase/sendMessage") {
				contentType(ContentType.Application.Json)
				setBody(buildJsonObject {
					put("chat_id", chatId)
					put("text", text)
					putJsonObject("reply_markup") {
						putJsonArray("inline_keyboard") {
							for (row in buttons) {
								addJsonArray {
									for ((label, callbackData) in row) {
										addJsonObject {
											put("text", label)
											put("callback_data", callbackData)
										}
									}
								}
							}
						}
					}
				}.toString())
			}
			val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
			body["ok"]?.jsonPrimitive?.boolean == true
		} catch (e: Exception) {
			logger.error("Error sending Telegram buttons: ${e.message}", e)
			false
		}
	}

	/**
	 * Edits an existing message — used for streaming/progressive updates.
	 */
	suspend fun editMessage(chatId: String, messageId: Long, newText: String): Boolean {
		return try {
			val response = httpClient.post("$apiBase/editMessageText") {
				contentType(ContentType.Application.Json)
				setBody(buildJsonObject {
					put("chat_id", chatId)
					put("message_id", messageId)
					put("text", newText)
				}.toString())
			}
			val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
			body["ok"]?.jsonPrimitive?.boolean == true
		} catch (e: Exception) {
			logger.error("Error editing Telegram message: ${e.message}", e)
			false
		}
	}

	private suspend fun pollUpdates() {
		while (coroutineContext.isActive) {
			try {
				val response = httpClient.get("$apiBase/getUpdates") {
					parameter("offset", lastUpdateId + 1)
					parameter("timeout", config.longPollTimeoutSeconds)
					parameter("allowed_updates", "[\"message\",\"callback_query\"]")
				}

				val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
				val ok = body["ok"]?.jsonPrimitive?.boolean ?: false
				if (!ok) continue

				val updates = body["result"]?.jsonArray ?: continue
				for (update in updates) {
					val updateObj = update.jsonObject
					val updateId = updateObj["update_id"]?.jsonPrimitive?.long ?: continue
					lastUpdateId = maxOf(lastUpdateId, updateId)

					// Handle regular messages
					val message = updateObj["message"]?.jsonObject
					if (message != null) {
						processMessage(message)
						continue
					}

					// Handle callback queries (inline button presses)
					val callback = updateObj["callback_query"]?.jsonObject
					if (callback != null) {
						processCallback(callback)
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				logger.error("Telegram polling error: ${e.message}", e)
				delay(5000) // Back off on error
			}
		}
	}

	private suspend fun processMessage(message: JsonObject) {
		val chatId = message["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.long?.toString() ?: return
		val text = message["text"]?.jsonPrimitive?.content ?: return
		val fromObj = message["from"]?.jsonObject ?: return
		val userId = fromObj["id"]?.jsonPrimitive?.long?.toString() ?: return
		val firstName = fromObj["first_name"]?.jsonPrimitive?.content ?: "User"
		val username = fromObj["username"]?.jsonPrimitive?.content ?: firstName
		val messageId = message["message_id"]?.jsonPrimitive?.long

		// In group chats, only respond to messages that mention the bot
		val chatType = message["chat"]?.jsonObject?.get("type")?.jsonPrimitive?.content ?: "private"
		if (chatType != "private" && !config.respondToAllGroupMessages) {
			val mentionsBot = text.contains("@${botUsername}") ||
				(text.startsWith("/") && text.contains("@${botUsername}"))
			if (!mentionsBot) return
		}

		val cleanText = text
			.replace("@${botUsername}", "")
			.replace(Regex("^/\\w+"), "") // Strip bot commands like /start
			.trim()

		if (cleanText.isEmpty()) return

		// Set "thinking" reaction
		if (messageId != null) {
			setReaction(chatId, messageId, "👀")
		}

		val inbound = InboundMessage(
			channelType = ChannelType.TELEGRAM,
			channelId = chatId,
			senderId = userId,
			senderName = username,
			content = cleanText,
			metadata = buildMap {
				messageId?.let { put("messageId", it.toString()) }
				put("chatType", chatType)
			}
		)

		try {
			messageHandler?.invoke(inbound)
		} catch (e: Exception) {
			logger.error("Error handling Telegram message: ${e.message}", e)
		}
	}

	private suspend fun processCallback(callback: JsonObject) {
		val callbackId = callback["id"]?.jsonPrimitive?.content
		val data = callback["data"]?.jsonPrimitive?.content ?: return
		val fromObj = callback["from"]?.jsonObject ?: return
		val userId = fromObj["id"]?.jsonPrimitive?.long?.toString() ?: return
		val username = fromObj["username"]?.jsonPrimitive?.content ?: "User"
		val chatId = callback["message"]?.jsonObject?.get("chat")?.jsonObject?.get("id")
			?.jsonPrimitive?.long?.toString() ?: return

		// Acknowledge the callback
		if (callbackId != null) {
			httpClient.post("$apiBase/answerCallbackQuery") {
				contentType(ContentType.Application.Json)
				setBody(buildJsonObject { put("callback_query_id", callbackId) }.toString())
			}
		}

		val inbound = InboundMessage(
			channelType = ChannelType.TELEGRAM,
			channelId = chatId,
			senderId = userId,
			senderName = username,
			content = data,
			metadata = mapOf("type" to "callback", "callbackId" to (callbackId ?: ""))
		)

		try {
			messageHandler?.invoke(inbound)
		} catch (e: Exception) {
			logger.error("Error handling Telegram callback: ${e.message}", e)
		}
	}

	companion object {
		private const val TELEGRAM_API = "https://api.telegram.org"
		private const val TELEGRAM_MAX_MESSAGE_LENGTH = 4096

		fun splitMessage(content: String, maxLength: Int): List<String> {
			if (content.length <= maxLength) return listOf(content)
			val chunks = mutableListOf<String>()
			var remaining = content
			while (remaining.isNotEmpty()) {
				if (remaining.length <= maxLength) {
					chunks.add(remaining)
					break
				}
				val splitIdx = remaining.lastIndexOf('\n', maxLength)
				val idx = if (splitIdx > 0) splitIdx else maxLength
				chunks.add(remaining.substring(0, idx))
				remaining = remaining.substring(idx).trimStart('\n')
			}
			return chunks
		}
	}
}
