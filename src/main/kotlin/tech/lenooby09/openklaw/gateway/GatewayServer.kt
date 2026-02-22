package tech.lenooby09.openklaw.gateway

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.websocket.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.agent.AgentLoop
import tech.lenooby09.openklaw.agent.ChatRequest
import tech.lenooby09.openklaw.config.GatewayConfig
import tech.lenooby09.openklaw.config.SecurityConfig
import tech.lenooby09.openklaw.memory.MemoryManager
import tech.lenooby09.openklaw.security.RateLimiter
import tech.lenooby09.openklaw.session.*
import tech.lenooby09.openklaw.tools.CanvasTool
import tech.lenooby09.openklaw.tools.ToolExecutionRequest
import tech.lenooby09.openklaw.tools.ToolRegistry
import tech.lenooby09.openklaw.messaging.ChannelRouter
import tech.lenooby09.openklaw.web.DashboardHtml

class GatewayServer(
	private val config: GatewayConfig,
	private val securityConfig: SecurityConfig,
	private val sessionManager: SessionManager,
	private val agentLoop: AgentLoop,
	private val startTime: Long,
	private val toolRegistry: ToolRegistry? = null,
	private val canvasTool: CanvasTool? = null,
	private val memoryManager: MemoryManager? = null,
	private val channelRouter: ChannelRouter? = null
) {
	private val logger = LoggerFactory.getLogger(GatewayServer::class.java)
	private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
	private val rateLimiter = RateLimiter(securityConfig)
	private val maxInputBytes = securityConfig.computeMaxInputSizeBytes()
	private val isLocalhost = config.bindAddress == "127.0.0.1" || config.bindAddress == "localhost" || config.bindAddress == "::1"

	fun start() {
		server = embeddedServer(CIO, port = config.port, host = config.bindAddress) {
			install(ContentNegotiation) {
				json(Json { prettyPrint = false; ignoreUnknownKeys = true })
			}
			install(WebSockets)
			install(CORS) {
				allowHost("localhost:${config.port}")
				allowHost("127.0.0.1:${config.port}")
				allowMethod(HttpMethod.Get)
				allowMethod(HttpMethod.Post)
				allowMethod(HttpMethod.Put)
				allowMethod(HttpMethod.Delete)
				allowMethod(HttpMethod.Options)
				allowHeader(HttpHeaders.ContentType)
				allowHeader("X-CSRF-Token")
				allowCredentials = true
			}
			intercept(ApplicationCallPipeline.Plugins) {
				call.response.header("X-Content-Type-Options", "nosniff")
				call.response.header("X-Frame-Options", "DENY")
				call.response.header("X-XSS-Protection", "1; mode=block")
				call.response.header("Referrer-Policy", "strict-origin-when-cross-origin")
				call.response.header("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'")
				if (!isLocalhost) {
					call.response.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
				}

				if (!isLocalhost && call.request.local.scheme != "https") {
					call.respond(HttpStatusCode.Forbidden, ErrorResponse("HTTPS required for non-localhost connections."))
					finish()
					return@intercept
				}
			}
			routing {
				dashboardRoute()
				authRoutes()
				apiRoutes()
				userManagementRoutes()
				storageBudgetRoutes()
				toolRoutes()
				messagingRoutes()
			}
		}.start(wait = false)

		logger.info("Gateway server started on ${config.bindAddress}:${config.port}")
	}

	fun stop() {
		server?.stop(gracePeriodMillis = 1000, timeoutMillis = 2000)
		sessionManager.clearAllSessions()
		logger.info("Gateway server stopped")
	}

	fun cleanupRateLimiter() {
		rateLimiter.cleanup()
	}

	private fun Routing.dashboardRoute() {
		get("/") {
			call.respondText(DashboardHtml.INDEX, ContentType.Text.Html)
		}
	}

	private fun Routing.authRoutes() {
		post("/api/signup") {
			if (sessionManager.hasUsers()) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Registration is closed. Users already exist."))
				return@post
			}
			val req = call.receiveBounded<SignupRequest>() ?: return@post
			if (req.username.isBlank() || req.password.isBlank()) {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Username and password are required."))
				return@post
			}
			if (!isValidUsername(req.username)) {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Username must be 3-32 characters: letters, digits, underscores, hyphens only."))
				return@post
			}
			if (req.password.length < 8) {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Password must be at least 8 characters."))
				return@post
			}
			val session = sessionManager.registerFirstAdmin(req.username, req.password, req.signupToken)
			if (session != null) {
				call.setSessionCookies(session)
				call.respond(LoginResponse(session.username, session.isAdmin))
			} else {
				call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid signup token."))
			}
		}

		post("/api/login") {
			val clientIp = call.request.local.remoteHost
			val waitMs = rateLimiter.checkAndRecord(clientIp)
			if (waitMs > 0) {
				call.response.header("Retry-After", ((waitMs / 1000) + 1).toString())
				call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Too many login attempts. Retry after ${(waitMs / 1000) + 1} seconds."))
				return@post
			}

			val req = call.receiveBounded<LoginRequest>() ?: return@post
			val session = sessionManager.authenticate(req.username, req.password)
			if (session != null) {
				rateLimiter.recordSuccess(clientIp)
				call.setSessionCookies(session)
				call.respond(LoginResponse(session.username, session.isAdmin))
			} else {
				call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid username or password."))
			}
		}

		post("/api/logout") {
			val session = call.requireAuth() ?: return@post
			sessionManager.removeSession(session.token)
			call.clearSessionCookies()
			call.respond(MessageResponse("Logged out."))
		}

		get("/api/me") {
			val session = call.requireAuth() ?: return@get
			call.respond(MeResponse(session.username, session.isAdmin))
		}

		get("/api/setup-required") {
			// Always return a response with the same structure to avoid leaking setup state to attackers.
			// The 'true'/'false' value is safe since the signup endpoint itself validates the token.
			call.respond(MessageResponse(if (!sessionManager.hasUsers()) "true" else "false"))
		}
	}

	private fun Routing.apiRoutes() {
		get("/api/stats") {
			call.requireAuth() ?: return@get
			call.respond(
				StatsResponse(
					totalUsers = sessionManager.getUserCount(),
					activeSessions = sessionManager.getActiveSessions(),
					activeConversations = agentLoop.getActiveConversationCount(),
					configuredModels = 0,
					uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000
				)
			)
		}

		post("/api/chat") {
			val session = call.requireAuth() ?: return@post
			if (!call.verifyCsrf(session)) return@post
			val req = call.receiveBounded<ChatRequest>() ?: return@post
			if (req.message.isBlank()) {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Message cannot be empty."))
				return@post
			}
			val response = agentLoop.chat(session.username, req)
			call.respond(response)
		}

		get("/api/conversations") {
			val session = call.requireAuth() ?: return@get
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@get
			}
			call.respond(agentLoop.listConversations())
		}

		get("/api/conversations/{id}") {
			val session = call.requireAuth() ?: return@get
			val id = call.parameters["id"] ?: run {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing conversation ID."))
				return@get
			}
			val conversation = agentLoop.getConversation(id)
			if (conversation == null) {
				call.respond(HttpStatusCode.NotFound, ErrorResponse("Conversation not found."))
				return@get
			}
			if (!session.isAdmin && conversation.username != session.username) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Access denied."))
				return@get
			}
			call.respond(conversation)
		}

		delete("/api/conversations/{id}") {
			val session = call.requireAuth() ?: return@delete
			if (!call.verifyCsrf(session)) return@delete
			val id = call.parameters["id"] ?: run {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing conversation ID."))
				return@delete
			}
			if (agentLoop.deleteConversation(id, session.username, session.isAdmin)) {
				call.respond(MessageResponse("Conversation deleted."))
			} else {
				call.respond(HttpStatusCode.NotFound, ErrorResponse("Conversation not found or access denied."))
			}
		}
	}

	private fun Routing.userManagementRoutes() {
		post("/api/users") {
			val session = call.requireAuth() ?: return@post
			if (!call.verifyCsrf(session)) return@post
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@post
			}
			val req = call.receiveBounded<CreateUserRequest>() ?: return@post
			if (req.username.isBlank() || req.password.isBlank()) {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Username and password are required."))
				return@post
			}
			if (!isValidUsername(req.username)) {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Username must be 3-32 characters: letters, digits, underscores, hyphens only."))
				return@post
			}
			if (req.password.length < 8) {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Password must be at least 8 characters."))
				return@post
			}
			if (sessionManager.createUser(req.username, req.password, req.isAdmin)) {
				call.respond(MessageResponse("User '${req.username}' created."))
			} else {
				call.respond(HttpStatusCode.Conflict, ErrorResponse("User '${req.username}' already exists."))
			}
		}

		get("/api/users") {
			val session = call.requireAuth() ?: return@get
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@get
			}
			call.respond(sessionManager.listUsers())
		}

		delete("/api/users/{username}") {
			val session = call.requireAuth() ?: return@delete
			if (!call.verifyCsrf(session)) return@delete
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@delete
			}
			val targetUser = call.parameters["username"] ?: run {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing username."))
				return@delete
			}
			if (sessionManager.deleteUser(targetUser, session.username)) {
				call.respond(MessageResponse("User '$targetUser' deleted."))
			} else {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Cannot delete user. User not found or cannot delete yourself."))
			}
		}

		post("/api/change-password") {
			val session = call.requireAuth() ?: return@post
			if (!call.verifyCsrf(session)) return@post
			val req = call.receiveBounded<ChangePasswordRequest>() ?: return@post
			if (req.newPassword.length < 8) {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("New password must be at least 8 characters."))
				return@post
			}
			if (sessionManager.changePassword(session.username, req.currentPassword, req.newPassword)) {
				call.respond(MessageResponse("Password changed. Please log in again."))
			} else {
				call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Current password is incorrect."))
			}
		}
	}

	private fun Routing.storageBudgetRoutes() {
		get("/api/storage-budgets") {
			val session = call.requireAuth() ?: return@get
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@get
			}
			val memFiles = memoryManager?.memoryFiles
			if (memFiles == null) {
				call.respond(emptyList<StorageBudgetInfo>())
				return@get
			}
			val users = sessionManager.listUsers()
			val budgets = users.map { user ->
				StorageBudgetInfo(
					username = user.username,
					budgetBytes = memFiles.getUserStorageBudget(user.username),
					usedBytes = memFiles.getUserProfileSize(user.username)
				)
			}
			call.respond(budgets)
		}

		put("/api/storage-budgets") {
			val session = call.requireAuth() ?: return@put
			if (!call.verifyCsrf(session)) return@put
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@put
			}
			val memFiles = memoryManager?.memoryFiles
			if (memFiles == null) {
				call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Memory system is not enabled."))
				return@put
			}
			val req = call.receiveBounded<StorageBudgetRequest>() ?: return@put
			if (req.budgetBytes < 0) {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Budget must be non-negative."))
				return@put
			}
			if (!isValidUsername(req.username)) {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid username format."))
				return@put
			}
			memFiles.setUserStorageBudget(req.username, req.budgetBytes)
			call.respond(MessageResponse("Storage budget for '${req.username}' set to ${req.budgetBytes} bytes."))
		}
	}

	private fun Routing.toolRoutes() {
		get("/api/tools") {
			call.requireAuth() ?: return@get
			if (toolRegistry == null) {
				call.respond(emptyList<Any>())
				return@get
			}
			call.respond(toolRegistry.listTools())
		}

		post("/api/tools/execute") {
			val session = call.requireAuth() ?: return@post
			if (!call.verifyCsrf(session)) return@post
			if (toolRegistry == null) {
				call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Tool execution engine is not enabled."))
				return@post
			}
			val req = call.receiveBounded<ToolExecutionRequest>() ?: return@post
			val result = toolRegistry.execute(req)
			call.respond(result)
		}

		get("/api/canvas") {
			call.requireAuth() ?: return@get
			if (canvasTool == null) {
				call.respond(emptyList<Any>())
				return@get
			}
			call.respond(canvasTool.getItems())
		}

		get("/api/canvas/{id}") {
			call.requireAuth() ?: return@get
			val id = call.parameters["id"] ?: run {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing canvas item ID."))
				return@get
			}
			if (canvasTool == null) {
				call.respond(HttpStatusCode.NotFound, ErrorResponse("Canvas not available."))
				return@get
			}
			val item = canvasTool.getItem(id)
			if (item == null) {
				call.respond(HttpStatusCode.NotFound, ErrorResponse("Canvas item not found."))
				return@get
			}
			call.respond(item)
		}
	}

	private val webhookRateLimiter = RateLimiter(
		SecurityConfig(
			rateLimitMaxAttempts = securityConfig.rateLimitMaxAttempts * 10,
			rateLimitWindowMs = securityConfig.rateLimitWindowMs,
			rateLimitBaseDelayMs = securityConfig.rateLimitBaseDelayMs
		)
	)

	private fun Routing.messagingRoutes() {
		// Messaging channel status endpoint
		get("/api/channels") {
			call.requireAuth() ?: return@get
			val channels = channelRouter?.getRegisteredChannels()?.map { ch ->
				mapOf(
					"type" to ch.channelType.name,
					"name" to ch.displayName,
					"connected" to ch.connected
				)
			} ?: emptyList()
			call.respond(channels)
		}

		// Account linking routes
		get("/api/channel-links") {
			val session = call.requireAuth() ?: return@get
			val links = sessionManager.getChannelLinks(session.username)
			call.respond(links)
		}

		post("/api/channel-links") {
			val session = call.requireAuth() ?: return@post
			if (!call.verifyCsrf(session)) return@post
			val req = call.receiveBounded<LinkAccountRequest>() ?: return@post
			val channelType = req.channelType.uppercase()
			if (channelType !in validChannelTypes) {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid channel type. Valid types: $validChannelTypes"))
				return@post
			}
			if (req.channelUserId.isBlank() || req.channelUserId.length > 100) {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid channel user ID."))
				return@post
			}
			val linked = sessionManager.linkChannelAccount(session.username, channelType, req.channelUserId)
			if (linked) {
				call.respond(MessageResponse("Channel account linked successfully."))
			} else {
				call.respond(HttpStatusCode.Conflict, ErrorResponse("Could not link account — it may already be linked to another user."))
			}
		}

		delete("/api/channel-links/{channelType}/{channelUserId}") {
			val session = call.requireAuth() ?: return@delete
			if (!call.verifyCsrf(session)) return@delete
			val channelType = call.parameters["channelType"]?.uppercase() ?: ""
			val channelUserId = call.parameters["channelUserId"] ?: ""
			val unlinked = sessionManager.unlinkChannelAccount(session.username, channelType, channelUserId)
			if (unlinked) {
				call.respond(MessageResponse("Channel account unlinked."))
			} else {
				call.respond(HttpStatusCode.NotFound, ErrorResponse("Link not found."))
			}
		}

		// Admin: view all channel links
		get("/api/admin/channel-links") {
			val session = call.requireAuth() ?: return@get
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@get
			}
			call.respond(sessionManager.getAllChannelLinks())
		}

		// Install webhook and WebSocket routes from registered channels (with rate limiting)
		channelRouter?.getRegisteredChannels()?.forEach { channel ->
			when (channel) {
				is tech.lenooby09.openklaw.messaging.WhatsAppChannel -> channel.installWebhookRoutes(this)
				is tech.lenooby09.openklaw.messaging.SlackChannel -> channel.installWebhookRoutes(this)
				is tech.lenooby09.openklaw.messaging.WebChatChannel -> channel.installWebSocketRoute(this)
			}
		}
	}

	fun cleanupWebhookRateLimiter() {
		webhookRateLimiter.cleanup()
	}

	private val validChannelTypes = setOf("DISCORD", "TELEGRAM", "WHATSAPP", "SLACK", "EMAIL", "WEBCHAT")

	private suspend fun ApplicationCall.requireAuth(): DashboardSession? {
		// Read session token from HttpOnly cookie
		val token = request.cookies["session_token"]
		if (token.isNullOrBlank()) {
			respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required."))
			return null
		}
		val session = sessionManager.validateSession(token)
		if (session == null) {
			respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session."))
			return null
		}
		return session
	}

	private suspend fun ApplicationCall.verifyCsrf(session: DashboardSession): Boolean {
		val csrfToken = request.header("X-CSRF-Token")
		if (csrfToken == null || !sessionManager.validateCsrfToken(session, csrfToken)) {
			respond(HttpStatusCode.Forbidden, ErrorResponse("Invalid or missing CSRF token."))
			return false
		}
		return true
	}

	private suspend inline fun <reified T : Any> ApplicationCall.receiveBounded(): T? {
		// Read raw bytes with a hard limit to prevent oversized payloads regardless of Content-Length header
		return try {
			val channel = receiveChannel()
			val buffer = ByteArray(maxInputBytes.toInt() + 1)
			var totalRead = 0
			while (totalRead <= maxInputBytes) {
				val read = channel.readAvailable(buffer, totalRead, buffer.size - totalRead)
				if (read == -1) break
				totalRead += read
			}
			if (totalRead > maxInputBytes) {
				respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("Request body exceeds maximum size of ${securityConfig.maxInputSizeMb} MB."))
				return null
			}
			val jsonString = buffer.decodeToString(0, totalRead)
			Json.decodeFromString<T>(jsonString)
		} catch (e: Exception) {
			respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body."))
			null
		}
	}

	private fun isValidUsername(username: String): Boolean {
		return username.matches(Regex("^[a-zA-Z0-9_-]{3,32}$"))
	}

	private fun ApplicationCall.setSessionCookies(session: DashboardSession) {
		val secure = if (!isLocalhost) "; Secure" else ""
		// HttpOnly session cookie — not accessible to JS
		response.header("Set-Cookie", "session_token=${session.token}; Path=/; HttpOnly; SameSite=Strict$secure")
		// Readable CSRF cookie — JS reads this for double-submit pattern
		response.cookies.append(
			Cookie(
				name = "csrf_token",
				value = session.csrfToken,
				path = "/",
				secure = !isLocalhost,
				extensions = mapOf("SameSite" to "Strict")
			)
		)
	}

	private fun ApplicationCall.clearSessionCookies() {
		val secure = if (!isLocalhost) "; Secure" else ""
		response.header("Set-Cookie", "session_token=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0$secure")
		response.cookies.append(
			Cookie(
				name = "csrf_token",
				value = "",
				path = "/",
				maxAge = 0,
				secure = !isLocalhost,
				extensions = mapOf("SameSite" to "Strict")
			)
		)
	}
}
