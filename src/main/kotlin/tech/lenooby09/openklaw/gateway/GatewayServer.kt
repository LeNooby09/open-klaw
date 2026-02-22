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
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.agent.AgentLoop
import tech.lenooby09.openklaw.agent.ChatRequest
import tech.lenooby09.openklaw.config.GatewayConfig
import tech.lenooby09.openklaw.config.SecurityConfig
import tech.lenooby09.openklaw.security.RateLimiter
import tech.lenooby09.openklaw.session.*
import tech.lenooby09.openklaw.web.DashboardHtml

class GatewayServer(
	private val config: GatewayConfig,
	private val securityConfig: SecurityConfig,
	private val sessionManager: SessionManager,
	private val agentLoop: AgentLoop,
	private val startTime: Long
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
			install(CORS) {
				allowHost("localhost:${config.port}")
				allowHost("127.0.0.1:${config.port}")
				allowMethod(HttpMethod.Get)
				allowMethod(HttpMethod.Post)
				allowMethod(HttpMethod.Put)
				allowMethod(HttpMethod.Delete)
				allowMethod(HttpMethod.Options)
				allowHeader(HttpHeaders.ContentType)
				allowHeader(HttpHeaders.Authorization)
				allowHeader("X-CSRF-Token")
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
			}
		}.start(wait = false)

		logger.info("Gateway server started on ${config.bindAddress}:${config.port}")
	}

	fun stop() {
		server?.stop(gracePeriodMillis = 1000, timeoutMillis = 2000)
		sessionManager.clearAllSessions()
		logger.info("Gateway server stopped")
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
			val req = call.receiveSanitized<SignupRequest>() ?: return@post
			if (req.username.isBlank() || req.password.isBlank()) {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Username and password are required."))
				return@post
			}
			if (req.password.length < 8) {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Password must be at least 8 characters."))
				return@post
			}
			val session = sessionManager.registerFirstAdmin(req.username, req.password, req.signupToken)
			if (session != null) {
				call.response.header("Set-Cookie", buildCsrfCookie(session.csrfToken))
				call.respond(LoginResponse(session.token, session.csrfToken, session.username, session.isAdmin))
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

			val req = call.receiveSanitized<LoginRequest>() ?: return@post
			val session = sessionManager.authenticate(req.username, req.password)
			if (session != null) {
				rateLimiter.recordSuccess(clientIp)
				call.response.header("Set-Cookie", buildCsrfCookie(session.csrfToken))
				call.respond(LoginResponse(session.token, session.csrfToken, session.username, session.isAdmin))
			} else {
				call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid username or password."))
			}
		}

		post("/api/logout") {
			val session = call.requireAuth() ?: return@post
			sessionManager.removeSession(session.token)
			call.response.header("Set-Cookie", "csrf_token=; Path=/; Max-Age=0; SameSite=Strict")
			call.respond(MessageResponse("Logged out."))
		}

		get("/api/me") {
			val session = call.requireAuth() ?: return@get
			call.respond(MeResponse(session.username, session.isAdmin))
		}

		get("/api/setup-required") {
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
			val req = call.receiveSanitized<ChatRequest>() ?: return@post
			val sanitizedMessage = sanitizeInput(req.message)
			if (sanitizedMessage.isBlank()) {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Message cannot be empty."))
				return@post
			}
			val response = agentLoop.chat(session.username, ChatRequest(sanitizedMessage, req.sessionId))
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
			if (!session.isAdmin) {
				call.respond(HttpStatusCode.Forbidden, ErrorResponse("Admin access required."))
				return@delete
			}
			val id = call.parameters["id"] ?: run {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing conversation ID."))
				return@delete
			}
			if (agentLoop.deleteConversation(id)) {
				call.respond(MessageResponse("Conversation deleted."))
			} else {
				call.respond(HttpStatusCode.NotFound, ErrorResponse("Conversation not found."))
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
			val req = call.receiveSanitized<CreateUserRequest>() ?: return@post
			if (req.username.isBlank() || req.password.isBlank()) {
				call.respond(HttpStatusCode.BadRequest, ErrorResponse("Username and password are required."))
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
			val req = call.receiveSanitized<ChangePasswordRequest>() ?: return@post
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

	private suspend fun ApplicationCall.requireAuth(): DashboardSession? {
		val header = request.header("Authorization")
		if (header == null || !header.startsWith("Bearer ")) {
			respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required."))
			return null
		}
		val token = header.removePrefix("Bearer ")
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

	private suspend inline fun <reified T : Any> ApplicationCall.receiveSanitized(): T? {
		val contentLength = request.header("Content-Length")?.toLongOrNull()
		if (contentLength != null && contentLength > maxInputBytes) {
			respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("Request body exceeds maximum size of ${securityConfig.maxInputSizeMb} MB."))
			return null
		}
		return try {
			receive<T>()
		} catch (e: Exception) {
			respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body."))
			null
		}
	}

	private fun sanitizeInput(input: String): String {
		return input
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&#x27;")
			.trim()
	}

	private fun buildCsrfCookie(csrfToken: String): String {
		val secure = if (!isLocalhost) "; Secure" else ""
		return "csrf_token=$csrfToken; Path=/; HttpOnly; SameSite=Strict$secure"
	}
}
