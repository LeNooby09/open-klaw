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
import tech.lenooby09.openklaw.config.DiscordConfig

/**
 * Discord messaging integration via the Discord Bot API.
 *
 * Uses HTTP polling of the Gateway REST API to receive messages and the
 * channels endpoint to send replies. Supports channels and DMs.
 */
class DiscordChannel(
	private val config: DiscordConfig
) : MessageChannel {

	private val logger = LoggerFactory.getLogger(DiscordChannel::class.java)
	private val json = Json { ignoreUnknownKeys = true; isLenient = true }
	private val httpClient = HttpClient(CIO) {
		engine {
			requestTimeout = 30_000
		}
	}

	override val channelType = ChannelType.DISCORD
	override val displayName = "Discord"
	override var connected: Boolean = false
		private set

	private var messageHandler: (suspend (InboundMessage) -> Unit)? = null
	private var pollingJob: Job? = null
	private var lastMessageId: String? = null
	private var botUserId: String? = null

	override suspend fun start() {
		if (config.botToken.isEmpty()) {
			logger.warn("Discord bot token not configured, skipping Discord channel")
			return
		}

		// Validate token by fetching bot user info
		try {
			val response = httpClient.get("${DISCORD_API}/users/@me") {
				header("Authorization", "Bot ${config.botToken}")
			}
			if (response.status == HttpStatusCode.OK) {
				val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
				botUserId = body["id"]?.jsonPrimitive?.content
				val botName = body["username"]?.jsonPrimitive?.content ?: "Unknown"
				connected = true
				logger.info("Discord bot connected as: $botName (ID: $botUserId)")
			} else {
				logger.error("Discord authentication failed: ${response.status}")
				return
			}
		} catch (e: Exception) {
			logger.error("Failed to connect to Discord: ${e.message}", e)
			return
		}

		// Start polling for new messages in configured channels
		pollingJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
			pollMessages()
		}
	}

	override suspend fun stop() {
		pollingJob?.cancel()
		pollingJob = null
		connected = false
		httpClient.close()
		logger.info("Discord channel stopped")
	}

	override suspend fun sendMessage(message: OutboundMessage): Boolean {
		if (!connected) return false

		return try {
 			if (!isValidSnowflake(message.channelId)) {
				logger.warn("Invalid Discord channel ID: ${message.channelId}")
				return false
			}
			// Split long messages (Discord has 2000 char limit)
			val chunks = splitMessage(message.content, DISCORD_MAX_MESSAGE_LENGTH)
			for (chunk in chunks) {
				val response = httpClient.post("${DISCORD_API}/channels/${message.channelId}/messages") {
					header("Authorization", "Bot ${config.botToken}")
					contentType(ContentType.Application.Json)
					setBody(buildJsonObject {
						put("content", chunk)
					}.toString())
				}
				if (response.status != HttpStatusCode.OK) {
					logger.warn("Discord send failed: ${response.status}")
					return false
				}
			}
			true
		} catch (e: Exception) {
			logger.error("Error sending Discord message: ${e.message}", e)
			false
		}
	}

	override fun onMessage(handler: suspend (InboundMessage) -> Unit) {
		messageHandler = handler
	}

	private suspend fun pollMessages() {
		while (coroutineContext.isActive) {
			try {
				for (channelId in config.channelIds) {
					fetchChannelMessages(channelId)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				logger.error("Discord polling error: ${e.message}", e)
			}
			delay(config.pollIntervalMs)
		}
	}

	private suspend fun fetchChannelMessages(channelId: String) {
		if (!isValidSnowflake(channelId)) {
			logger.warn("Skipping invalid Discord channel ID: $channelId")
			return
		}
		val url = buildString {
			append("${DISCORD_API}/channels/$channelId/messages?limit=10")
			lastMessageId?.let { append("&after=$it") }
		}

		val response = httpClient.get(url) {
			header("Authorization", "Bot ${config.botToken}")
		}

		if (response.status != HttpStatusCode.OK) return

		val messages = json.parseToJsonElement(response.bodyAsText()).jsonArray
		if (messages.isEmpty()) return

		// Process messages oldest-first
		val sorted = messages.sortedBy {
			it.jsonObject["id"]?.jsonPrimitive?.content ?: ""
		}

		for (msg in sorted) {
			val obj = msg.jsonObject
			val authorId = obj["author"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: continue
			val authorName = obj["author"]?.jsonObject?.get("username")?.jsonPrimitive?.content ?: "Unknown"
			val content = obj["content"]?.jsonPrimitive?.content ?: continue
			val msgId = obj["id"]?.jsonPrimitive?.content ?: continue

			// Skip messages from the bot itself
			if (authorId == botUserId) continue

			// Skip messages that don't mention the bot (unless in DM or configured to respond to all)
			if (!config.respondToAll && botUserId != null) {
				val mentions = obj["mentions"]?.jsonArray ?: JsonArray(emptyList())
				val mentionsBot = mentions.any {
					it.jsonObject["id"]?.jsonPrimitive?.content == botUserId
				}
				val isDM = obj["guild_id"] == null
				if (!mentionsBot && !isDM) continue
			}

			// Strip bot mention from content
			val cleanContent = content
				.replace(Regex("<@!?${botUserId}>"), "")
				.trim()

			if (cleanContent.isEmpty()) continue

			lastMessageId = msgId

			val inbound = InboundMessage(
				channelType = ChannelType.DISCORD,
				channelId = channelId,
				senderId = authorId,
				senderName = authorName,
				content = cleanContent,
				metadata = mapOf("messageId" to msgId)
			)

			try {
				messageHandler?.invoke(inbound)
			} catch (e: Exception) {
				logger.error("Error handling Discord message: ${e.message}", e)
			}
		}

		// Update last message ID to the newest
		sorted.lastOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content?.let {
			lastMessageId = it
		}
	}

	private fun isValidSnowflake(id: String): Boolean = id.matches(Regex("^\\d{1,20}$"))

	companion object {
		private const val DISCORD_API = "https://discord.com/api/v10"
		private const val DISCORD_MAX_MESSAGE_LENGTH = 2000

		fun splitMessage(content: String, maxLength: Int): List<String> {
			if (content.length <= maxLength) return listOf(content)
			val chunks = mutableListOf<String>()
			var remaining = content
			while (remaining.isNotEmpty()) {
				if (remaining.length <= maxLength) {
					chunks.add(remaining)
					break
				}
				// Try to split at a newline
				val splitIdx = remaining.lastIndexOf('\n', maxLength)
				val idx = if (splitIdx > 0) splitIdx else maxLength
				chunks.add(remaining.substring(0, idx))
				remaining = remaining.substring(idx).trimStart('\n')
			}
			return chunks
		}
	}
}
