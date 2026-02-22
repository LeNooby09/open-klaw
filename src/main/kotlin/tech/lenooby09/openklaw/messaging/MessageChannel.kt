package tech.lenooby09.openklaw.messaging

import kotlinx.serialization.Serializable

/**
 * Represents the source channel type for a message.
 */
@Serializable
enum class ChannelType {
	WEBCHAT, DISCORD, TELEGRAM, WHATSAPP, SLACK, EMAIL
}

/**
 * An inbound message received from any messaging channel.
 */
@Serializable
data class InboundMessage(
	val channelType: ChannelType,
	val channelId: String,
	val senderId: String,
	val senderName: String,
	val content: String,
	val timestamp: Long = System.currentTimeMillis(),
	val metadata: Map<String, String> = emptyMap()
)

/**
 * An outbound message to be sent to a messaging channel.
 */
@Serializable
data class OutboundMessage(
	val channelType: ChannelType,
	val channelId: String,
	val recipientId: String,
	val content: String,
	val metadata: Map<String, String> = emptyMap()
)

/**
 * Pluggable messaging channel interface. All transport integrations in Open-Klaw
 * implement this contract so messages can be received and sent uniformly.
 */
interface MessageChannel {
	/** The type of channel this implementation handles. */
	val channelType: ChannelType

	/** Human-readable name for this channel. */
	val displayName: String

	/** Whether this channel is currently connected and operational. */
	val connected: Boolean

	/** Start the channel (connect, begin polling/listening). */
	suspend fun start()

	/** Stop the channel (disconnect, clean up resources). */
	suspend fun stop()

	/** Send a message through this channel. */
	suspend fun sendMessage(message: OutboundMessage): Boolean

	/** Register a handler for inbound messages. */
	fun onMessage(handler: suspend (InboundMessage) -> Unit)
}
