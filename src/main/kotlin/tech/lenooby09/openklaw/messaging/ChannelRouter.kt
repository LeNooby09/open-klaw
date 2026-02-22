package tech.lenooby09.openklaw.messaging

import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.agent.AgentLoop
import tech.lenooby09.openklaw.agent.ChatRequest
import tech.lenooby09.openklaw.config.MessagingConfig
import tech.lenooby09.openklaw.session.SessionManager
import java.util.concurrent.ConcurrentHashMap
import java.util.LinkedHashMap

/**
 * Routes inbound messages from any registered MessageChannel to the AgentLoop,
 * and dispatches the agent's response back through the originating channel.
 *
 * Channel users must have a linked Open-Klaw account (via SessionManager) to
 * interact with the agent. Unlinked users receive a prompt to link their account.
 */
class ChannelRouter(
	private val agentLoop: AgentLoop,
	private val sessionManager: SessionManager,
	private val messagingConfig: MessagingConfig = MessagingConfig()
) {
	private val logger = LoggerFactory.getLogger(ChannelRouter::class.java)

	/** Registered channels keyed by ChannelType. */
	private val channels = ConcurrentHashMap<ChannelType, MessageChannel>()

	/** LRU session map: (channelType:senderId) → agent session ID for conversation continuity. */
	private val sessionMap = object : LinkedHashMap<String, String>(128, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
			return size > messagingConfig.channelSessionMapMaxSize
		}
	}
	private val sessionMapLock = Any()

	fun register(channel: MessageChannel) {
		channels[channel.channelType] = channel
		channel.onMessage { message -> handleInbound(message) }
		logger.info("Registered messaging channel: ${channel.displayName} (${channel.channelType})")
	}

	fun unregister(channelType: ChannelType) {
		channels.remove(channelType)
		logger.info("Unregistered messaging channel: $channelType")
	}

	fun getChannel(channelType: ChannelType): MessageChannel? = channels[channelType]

	fun getRegisteredChannels(): List<MessageChannel> = channels.values.toList()

	fun getChannelCount(): Int = channels.size

	fun getChannelStatuses(): Map<String, Boolean> =
		channels.values.associate { it.displayName to it.connected }

	suspend fun startAll() {
		channels.values.forEach { channel ->
			try {
				channel.start()
				logger.info("Started channel: ${channel.displayName}")
			} catch (e: Exception) {
				logger.error("Failed to start channel ${channel.displayName}: ${e.message}", e)
			}
		}
	}

	suspend fun stopAll() {
		channels.values.forEach { channel ->
			try {
				channel.stop()
				logger.info("Stopped channel: ${channel.displayName}")
			} catch (e: Exception) {
				logger.error("Failed to stop channel ${channel.displayName}: ${e.message}", e)
			}
		}
	}

	private suspend fun handleInbound(message: InboundMessage) {
		val channelTypeName = message.channelType.name

		// Resolve linked account — reject unlinked users
		val username = sessionManager.resolveChannelUser(channelTypeName, message.senderId)
		if (username == null) {
			logger.info("Unlinked channel user: ${message.channelType} senderId=${message.senderId}")
			sendUnlinkedResponse(message)
			return
		}

		logger.info("Inbound message from ${message.channelType} user=${username} channel=${message.channelId}")

		// Enforce input length limit
		val maxLength = messagingConfig.maxChannelMessageLength
		val content = if (message.content.length > maxLength) {
			val truncated = message.content.take(maxLength)
			logger.warn("Truncated oversized channel message from $username (${message.content.length} > $maxLength)")
			truncated
		} else {
			message.content
		}

		if (content.isBlank()) return

		try {
			val sessionId = synchronized(sessionMapLock) {
				sessionMap.getOrDefault("${channelTypeName}:${message.senderId}", "")
			}
			val request = ChatRequest(message = content, sessionId = sessionId)
			val response = agentLoop.chat(username, request)

			// Remember session for continuity (LRU-evicted)
			synchronized(sessionMapLock) {
				sessionMap["${channelTypeName}:${message.senderId}"] = response.sessionId
			}

			// Send response back through the originating channel
			val channel = channels[message.channelType]
			if (channel != null) {
				val outbound = OutboundMessage(
					channelType = message.channelType,
					channelId = message.channelId,
					recipientId = message.senderId,
					content = response.message.content
				)
				val sent = channel.sendMessage(outbound)
				if (!sent) {
					logger.warn("Failed to send response back to ${message.channelType} user=$username")
				}
			} else {
				logger.warn("No channel registered for ${message.channelType}, cannot send response")
			}
		} catch (e: Exception) {
			logger.error("Error handling inbound message from ${message.channelType}: ${e.message}", e)

			// Attempt to send error message back
			try {
				val channel = channels[message.channelType]
				channel?.sendMessage(
					OutboundMessage(
						channelType = message.channelType,
						channelId = message.channelId,
						recipientId = message.senderId,
						content = "⚠️ Sorry, I encountered an error processing your message. Please try again."
					)
				)
			} catch (sendError: Exception) {
				logger.error("Failed to send error response: ${sendError.message}")
			}
		}
	}

	private suspend fun sendUnlinkedResponse(message: InboundMessage) {
		val channel = channels[message.channelType] ?: return
		try {
			channel.sendMessage(
				OutboundMessage(
					channelType = message.channelType,
					channelId = message.channelId,
					recipientId = message.senderId,
					content = "🔒 Your ${message.channelType.name} account is not linked to an Open-Klaw user. " +
						"Please log into the Open-Klaw dashboard and link your channel account " +
						"(your ${message.channelType.name} ID: ${message.senderId}) under Account Settings."
				)
			)
		} catch (e: Exception) {
			logger.error("Failed to send unlinked response: ${e.message}")
		}
	}

	fun getSessionCount(): Int = synchronized(sessionMapLock) { sessionMap.size }

	fun clearSession(channelType: ChannelType, senderId: String) {
		synchronized(sessionMapLock) {
			sessionMap.remove("${channelType}:${senderId}")
		}
	}

	/**
	 * Periodic cleanup: remove session entries for channel identities no longer linked.
	 */
	fun cleanupUnlinkedSessions() {
		synchronized(sessionMapLock) {
			val keysToRemove = sessionMap.keys.filter { key ->
				val parts = key.split(":", limit = 2)
				if (parts.size == 2) {
					sessionManager.resolveChannelUser(parts[0], parts[1]) == null
				} else true
			}
			keysToRemove.forEach { sessionMap.remove(it) }
			if (keysToRemove.isNotEmpty()) {
				logger.info("Cleaned up ${keysToRemove.size} unlinked channel sessions")
			}
		}
	}
}
