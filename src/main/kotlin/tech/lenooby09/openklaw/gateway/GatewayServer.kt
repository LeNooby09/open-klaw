package tech.lenooby09.openklaw.gateway

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.agent.AgentLoop
import tech.lenooby09.openklaw.agent.ChatRequest
import tech.lenooby09.openklaw.config.GatewayConfig
import tech.lenooby09.openklaw.config.SecurityConfig
import tech.lenooby09.openklaw.health.HealthCheckManager
import tech.lenooby09.openklaw.memory.MemoryManager
import tech.lenooby09.openklaw.messaging.ChannelRouter
import tech.lenooby09.openklaw.observability.UsageTracker
import tech.lenooby09.openklaw.scheduler.WebhookTriggerManager
import tech.lenooby09.openklaw.security.RateLimiter
import tech.lenooby09.openklaw.security.UserPermissionManager
import tech.lenooby09.openklaw.security.UserToolPermissions
import tech.lenooby09.openklaw.session.*
import tech.lenooby09.openklaw.skills.SkillManager
import tech.lenooby09.openklaw.skills.SkillRegistryClient
import tech.lenooby09.openklaw.skills.SkillSource
import tech.lenooby09.openklaw.tools.CanvasTool
import tech.lenooby09.openklaw.tools.ToolExecutionRequest
import tech.lenooby09.openklaw.tools.ToolRegistry
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
	private val channelRouter: ChannelRouter? = null,
	private val webhookTriggerManager: WebhookTriggerManager? = null,
	private val skillManager: SkillManager? = null,
	private val skillRegistryClient: SkillRegistryClient? = null,
	private val userPermissionManager: UserPermissionManager? = null,
	private val healthCheckManager: HealthCheckManager? = null,
	private val usageTracker: UsageTracker? = null
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
			}
			routing {
				dashboardRoute()
				authRoutes()
				apiRoutes()
				userManagementRoutes()
				storageBudgetRoutes()
				toolRoutes()
				messagingRoutes()
				webhookTriggerManager?.installRoutes(this, maxInputBytes)
 			webhookListRoute()
				skillRoutes()
				permissionRoutes()
				healthRoutes()
				observabilityRoutes()
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
			logger.info("Signup request received")
			if (sessionManager.hasUsers()) {
				logger.info("Signup rejected: users already exist")
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
				logger.info("Signup completed successfully for user: ${req.username}")
			} else {
				logger.info("Signup failed: invalid signup token")
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
 		val allowedTools = userPermissionManager?.resolveAllowedTools(session.username, session.isAdmin)
			usageTracker?.recordMessage(session.username)
			val response = agentLoop.chat(session.username, req, allowedTools)
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
		webhookTriggerManager?.cleanupRateLimiter()
	}

	private fun Routing.webhookListRoute() {
		get("/api/webhooks") {
			val session = call.requireAuth() ?: return@get
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@get
			}
			val triggerList = webhookTriggerManager?.listTriggers()?.map { t ->
				mapOf(
					"id" to t.id,
					"name" to t.name,
					"enabled" to t.enabled.toString(),
					"url" to "/api/webhooks/${t.id}"
				)
			} ?: emptyList()
			call.respond(triggerList)
		}
	}

	@kotlinx.serialization.Serializable
	private data class SkillActionRequest(val skillId: String)

	@kotlinx.serialization.Serializable
	private data class SkillInstallRequest(val content: String, val source: String = "WORKSPACE")

	@kotlinx.serialization.Serializable
	private data class RegistrySearchRequest(val query: String, val page: Int = 1)

	@kotlinx.serialization.Serializable
	private data class RegistryInstallRequest(val skillId: String)

	private fun Routing.skillRoutes() {
		// List all skills
		get("/api/skills") {
			call.requireAuth() ?: return@get
			if (skillManager == null) {
				call.respond(emptyList<Any>())
				return@get
			}
			call.respond(skillManager.getAllSkills())
		}

		// Get a specific skill
		get("/api/skills/{id}") {
			call.requireAuth() ?: return@get
			val id = call.parameters["id"] ?: run {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing skill ID."))
				return@get
			}
			val skill = skillManager?.getSkill(id)
			if (skill == null) {
				call.respond(HttpStatusCode.NotFound, ErrorResponse("Skill not found."))
				return@get
			}
			call.respond(skill)
		}

		// Get pending skills (admin)
		get("/api/skills/pending") {
			val session = call.requireAuth() ?: return@get
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@get
			}
			call.respond(skillManager?.getPendingSkills() ?: emptyList())
		}

		// Approve a pending skill (admin)
		post("/api/skills/approve") {
			val session = call.requireAuth() ?: return@post
			if (!call.verifyCsrf(session)) return@post
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@post
			}
			val req = call.receiveBounded<SkillActionRequest>() ?: return@post
			if (skillManager?.approveSkill(req.skillId) == true) {
				call.respond(MessageResponse("Skill '${req.skillId}' approved."))
			} else {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not approve skill. It may not exist or is not in PENDING state."))
			}
		}

		// Reject a pending skill (admin)
		post("/api/skills/reject") {
			val session = call.requireAuth() ?: return@post
			if (!call.verifyCsrf(session)) return@post
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@post
			}
			val req = call.receiveBounded<SkillActionRequest>() ?: return@post
			if (skillManager?.rejectSkill(req.skillId) == true) {
				call.respond(MessageResponse("Skill '${req.skillId}' rejected."))
			} else {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not reject skill."))
			}
		}

		// Disable an active skill (admin)
		post("/api/skills/disable") {
			val session = call.requireAuth() ?: return@post
			if (!call.verifyCsrf(session)) return@post
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@post
			}
			val req = call.receiveBounded<SkillActionRequest>() ?: return@post
			if (skillManager?.disableSkill(req.skillId) == true) {
				call.respond(MessageResponse("Skill '${req.skillId}' disabled."))
			} else {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not disable skill."))
			}
		}

		// Enable a disabled/rejected skill (admin)
		post("/api/skills/enable") {
			val session = call.requireAuth() ?: return@post
			if (!call.verifyCsrf(session)) return@post
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@post
			}
			val req = call.receiveBounded<SkillActionRequest>() ?: return@post
			if (skillManager?.enableSkill(req.skillId) == true) {
				call.respond(MessageResponse("Skill '${req.skillId}' enabled."))
			} else {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not enable skill."))
			}
		}

		// Remove a non-bundled skill (admin)
		delete("/api/skills/{id}") {
			val session = call.requireAuth() ?: return@delete
			if (!call.verifyCsrf(session)) return@delete
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@delete
			}
			val id = call.parameters["id"] ?: run {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing skill ID."))
				return@delete
			}
			if (skillManager?.removeSkill(id) == true) {
				call.respond(MessageResponse("Skill '$id' removed."))
			} else {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Could not remove skill. Bundled skills cannot be removed."))
			}
		}

		// Install a skill from content (admin)
		post("/api/skills/install") {
			val session = call.requireAuth() ?: return@post
			if (!call.verifyCsrf(session)) return@post
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@post
			}
			val req = call.receiveBounded<SkillInstallRequest>() ?: return@post
			val source = try { SkillSource.valueOf(req.source.uppercase()) } catch (_: Exception) { SkillSource.WORKSPACE }
			val installed = skillManager?.installSkill(req.content, source, autoApprove = true)
			if (installed != null) {
				call.respond(installed)
			} else {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Failed to install skill. Check content format and skill limit."))
			}
		}

		// Search local skills
		get("/api/skills/search") {
			call.requireAuth() ?: return@get
			val query = call.request.queryParameters["q"] ?: ""
			call.respond(skillManager?.searchSkills(query) ?: emptyList())
		}

		// Registry: search
		post("/api/skills/registry/search") {
			val session = call.requireAuth() ?: return@post
			if (!call.verifyCsrf(session)) return@post
			val req = call.receiveBounded<RegistrySearchRequest>() ?: return@post
			val result = skillRegistryClient?.search(req.query, req.page) ?: SkillRegistryClient.SearchResult()
			call.respond(result)
		}

		// Registry: install by ID
		post("/api/skills/registry/install") {
			val session = call.requireAuth() ?: return@post
			if (!call.verifyCsrf(session)) return@post
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@post
			}
			val req = call.receiveBounded<RegistryInstallRequest>() ?: return@post
			val installed = skillRegistryClient?.install(req.skillId)
			if (installed != null) {
				call.respond(installed)
			} else {
				call.respond(HttpStatusCode.NotFound, ErrorResponse("Skill not found in registry."))
			}
		}

		// Registry: publish a local skill (admin)
		post("/api/skills/registry/publish") {
			val session = call.requireAuth() ?: return@post
			if (!call.verifyCsrf(session)) return@post
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@post
			}
			val req = call.receiveBounded<SkillActionRequest>() ?: return@post
			val result = skillRegistryClient?.publish(req.skillId)
				?: SkillRegistryClient.PublishResult(false, "Registry client not available.")
			call.respond(result)
		}
	}

	private fun Routing.observabilityRoutes() {
		if (usageTracker == null) return

		// Global usage stats (admin only)
		get("/api/usage") {
			val session = call.requireAuth() ?: return@get
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@get
			}
			call.respond(usageTracker.getGlobalStats())
		}

		// Per-user usage stats (admin only)
		get("/api/usage/users") {
			val session = call.requireAuth() ?: return@get
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@get
			}
			call.respond(usageTracker.getAllUserStats())
		}

		// Presence / typing indicators
		get("/api/presence") {
			call.requireAuth() ?: return@get
			call.respond(usageTracker.getPresentUsers())
		}

		// Heartbeat — update user presence
		post("/api/presence/heartbeat") {
			val session = call.requireAuth() ?: return@post
			usageTracker.updatePresence(session.username)
			call.respond(MessageResponse("OK"))
		}
	}

	private fun Routing.healthRoutes() {
		// Public health endpoint (no auth required) — returns aggregate status only
		// Detailed per-component info is behind /api/health/diagnostics (admin only)
		get("/api/health") {
			if (healthCheckManager == null) {
				call.respond(MessageResponse("OK"))
				return@get
			}
			val report = healthCheckManager.runAll()
			val statusCode = when (report.status) {
				tech.lenooby09.openklaw.health.HealthStatus.HEALTHY -> HttpStatusCode.OK
				tech.lenooby09.openklaw.health.HealthStatus.DEGRADED -> HttpStatusCode.OK
				tech.lenooby09.openklaw.health.HealthStatus.UNHEALTHY -> HttpStatusCode.ServiceUnavailable
			}
			// Only expose aggregate status publicly — no component details
			call.respond(statusCode, MessageResponse(report.status.name))
		}

		// Detailed diagnostics (admin only)
		get("/api/health/diagnostics") {
			val session = call.requireAuth() ?: return@get
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@get
			}
			if (healthCheckManager == null) {
				call.respond(MessageResponse("Health check manager not configured."))
				return@get
			}
			call.respond(healthCheckManager.runDiagnostics())
		}

		// Individual health check (admin only)
		get("/api/health/{name}") {
			val session = call.requireAuth() ?: return@get
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@get
			}
			if (healthCheckManager == null) {
				call.respond(HttpStatusCode.NotFound, ErrorResponse("Health check manager not configured."))
				return@get
			}
			val name = call.parameters["name"] ?: run {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing check name."))
				return@get
			}
			val result = healthCheckManager.runCheck(name)
			if (result != null) {
				call.respond(result)
			} else {
				call.respond(HttpStatusCode.NotFound, ErrorResponse("Health check '$name' not found."))
			}
		}
	}

	private fun Routing.permissionRoutes() {
		if (userPermissionManager == null) return

		// List all user permissions (admin only)
		get("/api/permissions") {
			val session = call.requireAuth() ?: return@get
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@get
			}
			call.respond(userPermissionManager.listPermissions())
		}

		// Get permissions for a specific user (admin only)
		get("/api/permissions/{username}") {
			val session = call.requireAuth() ?: return@get
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@get
			}
			val username = call.parameters["username"] ?: run {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing username."))
				return@get
			}
			val tools = userPermissionManager.getUserTools(username)
			if (tools != null) {
				call.respond(UserToolPermissions(username, tools))
			} else {
				call.respond(MessageResponse("User '$username' has unrestricted access."))
			}
		}

		// Set permissions for a user (admin only)
		post("/api/permissions/{username}") {
			val session = call.requireAuth() ?: return@post
			if (!call.verifyCsrf(session)) return@post
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@post
			}
			val username = call.parameters["username"] ?: run {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing username."))
				return@post
			}
			if (!isValidUsername(username)) {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid username format."))
				return@post
			}
			val req = call.receiveBounded<SetPermissionsRequest>() ?: return@post
			userPermissionManager.setUserTools(username, req.allowedTools.toSet())
			call.respond(MessageResponse("Permissions updated for user '$username'."))
		}

		// Remove permissions for a user (reverts to unrestricted) (admin only)
		delete("/api/permissions/{username}") {
			val session = call.requireAuth() ?: return@delete
			if (!call.verifyCsrf(session)) return@delete
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@delete
			}
			val username = call.parameters["username"] ?: run {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing username."))
				return@delete
			}
			if (userPermissionManager.removeUserPermissions(username)) {
				call.respond(MessageResponse("Permissions removed for user '$username'. Access is now unrestricted."))
			} else {
				call.respond(HttpStatusCode.NotFound, ErrorResponse("No permission entry found for user '$username'."))
			}
		}
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
		return try {
			val text = receiveText()
			if (text.toByteArray().size > maxInputBytes) {
				respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("Request body exceeds maximum size of ${securityConfig.maxInputSizeMb} MB."))
				return null
			}
			Json.decodeFromString<T>(text)
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
