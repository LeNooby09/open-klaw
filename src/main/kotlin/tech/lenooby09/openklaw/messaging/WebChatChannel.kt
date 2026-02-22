package tech.lenooby09.openklaw.messaging

import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.session.SessionManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Built-in WebChat messaging channel served directly from the gateway.
 *
 * Uses WebSocket connections for real-time bi-directional communication.
 * Requires authentication via session token (query param or first frame).
 * Each connected client gets a unique session, and messages are routed
 * through the standard ChannelRouter → AgentLoop pipeline.
 */
class WebChatChannel(
	private val sessionManager: SessionManager
) : MessageChannel {

	private val logger = LoggerFactory.getLogger(WebChatChannel::class.java)
	private val json = Json { ignoreUnknownKeys = true; isLenient = true }

	override val channelType = ChannelType.WEBCHAT
	override val displayName = "WebChat"
	override var connected: Boolean = false
		private set

	private var messageHandler: (suspend (InboundMessage) -> Unit)? = null

	/** Active WebSocket sessions keyed by client ID. */
	private val activeSessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
	/** Maps client ID → authenticated username. */
	private val clientUsernames = ConcurrentHashMap<String, String>()
	private val clientCounter = AtomicLong(0)

	override suspend fun start() {
		connected = true
		logger.info("WebChat channel started")
	}

	override suspend fun stop() {
		connected = false
		activeSessions.values.forEach { session ->
			try {
				session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down"))
			} catch (_: Exception) {}
		}
		activeSessions.clear()
		clientUsernames.clear()
		logger.info("WebChat channel stopped")
	}

	override suspend fun sendMessage(message: OutboundMessage): Boolean {
		val session = activeSessions[message.recipientId] ?: return false

		return try {
			val payload = buildJsonObject {
				put("type", "message")
				put("content", message.content)
				put("timestamp", System.currentTimeMillis())
			}.toString()
			session.send(Frame.Text(payload))
			true
		} catch (e: Exception) {
			logger.error("Error sending WebChat message to ${message.recipientId}: ${e.message}")
			activeSessions.remove(message.recipientId)
			clientUsernames.remove(message.recipientId)
			false
		}
	}

	override fun onMessage(handler: suspend (InboundMessage) -> Unit) {
		messageHandler = handler
	}

	/**
	 * Installs the WebSocket route for WebChat into a Ktor routing block.
	 * Requires a valid session_token query parameter for authentication.
	 */
	fun installWebSocketRoute(routing: Routing) {
		routing.apply {
			webSocket("/ws/chat") {
				// Authenticate via query parameter
				val token = call.request.queryParameters["token"]
				if (token.isNullOrBlank()) {
					close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
					return@webSocket
				}

				val dashboardSession = sessionManager.validateSession(token)
				if (dashboardSession == null) {
					close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid or expired session"))
					return@webSocket
				}

				val clientId = "webchat_${clientCounter.incrementAndGet()}"
				val username = dashboardSession.username
				activeSessions[clientId] = this
				clientUsernames[clientId] = username
				logger.info("WebChat client connected: $clientId (user=$username)")

				// Send welcome message
				val welcome = buildJsonObject {
					put("type", "connected")
					put("clientId", clientId)
					put("username", username)
					put("message", "Connected to Open-Klaw WebChat")
				}.toString()
				send(Frame.Text(welcome))

				try {
					for (frame in incoming) {
						if (frame is Frame.Text) {
							val text = frame.readText()
							handleClientMessage(clientId, username, text)
						}
					}
				} catch (_: ClosedReceiveChannelException) {
					logger.debug("WebChat client disconnected: $clientId")
				} catch (e: Exception) {
					logger.error("WebChat error for client $clientId: ${e.message}")
				} finally {
					activeSessions.remove(clientId)
					clientUsernames.remove(clientId)
					logger.info("WebChat client removed: $clientId (${activeSessions.size} remaining)")
				}
			}
		}
	}

	private suspend fun handleClientMessage(clientId: String, authenticatedUsername: String, rawText: String) {
		try {
			val msgObj = json.parseToJsonElement(rawText).jsonObject
			val type = msgObj["type"]?.jsonPrimitive?.content ?: "message"
			val content = msgObj["content"]?.jsonPrimitive?.content ?: return
			// Username is always derived from the authenticated session, never from client input
			val username = authenticatedUsername

			when (type) {
				"message" -> {
					val inbound = InboundMessage(
						channelType = ChannelType.WEBCHAT,
						channelId = clientId,
						senderId = clientId,
						senderName = username,
						content = content
					)
					messageHandler?.invoke(inbound)
				}
				"ping" -> {
					val session = activeSessions[clientId]
					session?.send(Frame.Text(buildJsonObject {
						put("type", "pong")
						put("timestamp", System.currentTimeMillis())
					}.toString()))
				}
			}
		} catch (e: Exception) {
			logger.error("Error processing WebChat message from $clientId: ${e.message}")

			// Try to send error response
			try {
				activeSessions[clientId]?.send(Frame.Text(buildJsonObject {
					put("type", "error")
					put("message", "Failed to process message")
				}.toString()))
			} catch (_: Exception) {}
		}
	}

	fun getActiveConnectionCount(): Int = activeSessions.size
}
