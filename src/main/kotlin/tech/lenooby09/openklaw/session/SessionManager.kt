package tech.lenooby09.openklaw.session

import at.favre.lib.crypto.bcrypt.BCrypt
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.AuthConfig
import tech.lenooby09.openklaw.config.SecurityConfig
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.fixedRateTimer

data class DashboardSession(
	val token: String,
	val csrfToken: String,
	val username: String,
	val isAdmin: Boolean,
	val createdAt: Long = System.currentTimeMillis(),
	val expiresAt: Long = createdAt + 24 * 60 * 60 * 1000
)

data class UserAccount(
	val username: String,
	val passwordHash: String,
	val isAdmin: Boolean = false,
	val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val username: String, val isAdmin: Boolean)

@Serializable
data class MeResponse(val username: String, val isAdmin: Boolean)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class StatsResponse(
	val totalUsers: Int,
	val activeSessions: Int,
	val activeConversations: Int,
	val configuredModels: Int,
	val uptimeSeconds: Long
)

@Serializable
data class SignupRequest(val username: String, val password: String, val signupToken: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class CreateUserRequest(val username: String, val password: String, val isAdmin: Boolean = false)

@Serializable
data class DeleteUserRequest(val username: String)

@Serializable
data class UserInfo(val username: String, val isAdmin: Boolean, val createdAt: Long)

@Serializable
data class StorageBudgetRequest(val username: String, val budgetBytes: Long)

@Serializable
data class StorageBudgetInfo(val username: String, val budgetBytes: Long, val usedBytes: Long)

@Serializable
data class LinkAccountRequest(val channelType: String, val channelUserId: String)

@Serializable
data class UnlinkAccountRequest(val channelType: String, val channelUserId: String)

@Serializable
data class ChannelLinkInfo(val channelType: String, val channelUserId: String, val linkedAt: Long)

class SessionManager(
	private val authConfig: AuthConfig = AuthConfig(),
	private val securityConfig: SecurityConfig = SecurityConfig()
) {
	private val logger = LoggerFactory.getLogger(SessionManager::class.java)
	private val sessions = ConcurrentHashMap<String, DashboardSession>()
	private val users = ConcurrentHashMap<String, UserAccount>()
	private var cleanupTimer: Timer? = null
	private val cleanupCallbacks = mutableListOf<() -> Unit>()

	/**
	 * Account linking: maps "channelType:channelUserId" → registered username.
	 * Allows messaging channel users to be resolved to authenticated Open-Klaw accounts.
	 */
	private val channelLinks = ConcurrentHashMap<String, ChannelLinkEntry>()

	private data class ChannelLinkEntry(
		val username: String,
		val channelType: String,
		val channelUserId: String,
		val linkedAt: Long = System.currentTimeMillis()
	)

	@Volatile
	var signupToken: String? = null
		private set

	init {
		if (users.isEmpty()) {
			signupToken = generateSignupToken()
		}
	}

	/**
	 * Registers a callback to be invoked during each periodic cleanup cycle.
	 */
	fun onCleanup(callback: () -> Unit) {
		cleanupCallbacks.add(callback)
	}

	fun startCleanupScheduler() {
		val intervalMs = securityConfig.sessionCleanupIntervalMinutes * 60 * 1000L
		cleanupTimer = fixedRateTimer("session-cleanup", daemon = true, initialDelay = intervalMs, period = intervalMs) {
			cleanupExpiredSessions()
			cleanupCallbacks.forEach { callback ->
				try {
					callback()
				} catch (e: Exception) {
					logger.error("Cleanup callback failed: ${e.message}", e)
				}
			}
		}
		logger.info("Session cleanup scheduler started (every ${securityConfig.sessionCleanupIntervalMinutes} minutes)")
	}

	fun stopCleanupScheduler() {
		cleanupTimer?.cancel()
		cleanupTimer = null
	}

	fun registerFirstAdmin(username: String, password: String, token: String): DashboardSession? {
		if (signupToken == null || token != signupToken) return null
		if (users.isNotEmpty()) return null

		val hash = BCrypt.withDefaults().hashToString(authConfig.bcryptCost, password.toCharArray())
		users[username] = UserAccount(username, hash, isAdmin = true)
		signupToken = null
		logger.info("First admin user registered: $username")

		return createSession(username, isAdmin = true)
	}

	fun authenticate(username: String, password: String): DashboardSession? {
		val user = users[username] ?: return null
		val result = BCrypt.verifyer().verify(password.toCharArray(), user.passwordHash)
		if (!result.verified) return null

		return createSession(username, user.isAdmin)
	}

	fun validateSession(token: String): DashboardSession? {
		val session = sessions[token] ?: return null
		if (System.currentTimeMillis() > session.expiresAt) {
			sessions.remove(token)
			return null
		}
		return session
	}

	fun validateCsrfToken(session: DashboardSession, csrfToken: String): Boolean {
		return session.csrfToken == csrfToken
	}

	fun removeSession(token: String) {
		sessions.remove(token)
	}

	fun clearAllSessions() {
		sessions.clear()
	}

	fun getActiveSessions(): Int = sessions.count { System.currentTimeMillis() <= it.value.expiresAt }

	fun getUserCount(): Int = users.size

	fun hasUsers(): Boolean = users.isNotEmpty()

	fun createUser(username: String, password: String, isAdmin: Boolean = false): Boolean {
		if (users.containsKey(username)) return false
		val hash = BCrypt.withDefaults().hashToString(authConfig.bcryptCost, password.toCharArray())
		users[username] = UserAccount(username, hash, isAdmin)
		logger.info("User created: $username (admin=$isAdmin)")
		return true
	}

	fun deleteUser(username: String, requestingUser: String): Boolean {
		if (username == requestingUser) return false
		val removed = users.remove(username) != null
		if (removed) {
			sessions.entries.removeIf { it.value.username == username }
			logger.info("User deleted: $username (by $requestingUser)")
		}
		return removed
	}

	fun changePassword(username: String, currentPassword: String, newPassword: String): Boolean {
		val user = users[username] ?: return false
		val result = BCrypt.verifyer().verify(currentPassword.toCharArray(), user.passwordHash)
		if (!result.verified) return false
		val newHash = BCrypt.withDefaults().hashToString(authConfig.bcryptCost, newPassword.toCharArray())
		users[username] = user.copy(passwordHash = newHash)
		sessions.entries.removeIf { it.value.username == username }
		logger.info("Password changed for user: $username")
		return true
	}

	fun listUsers(): List<UserInfo> = users.values.map { UserInfo(it.username, it.isAdmin, it.createdAt) }

	// --- Account Linking for Messaging Channels ---

	/**
	 * Links a messaging channel identity to a registered user.
	 * Returns true if linked successfully, false if user doesn't exist or link already exists for another user.
	 */
	fun linkChannelAccount(username: String, channelType: String, channelUserId: String): Boolean {
		if (!users.containsKey(username)) return false
		val key = "$channelType:$channelUserId"
		val existing = channelLinks[key]
		if (existing != null && existing.username != username) return false
		channelLinks[key] = ChannelLinkEntry(username, channelType, channelUserId)
		logger.info("Channel account linked: $channelType:$channelUserId → $username")
		return true
	}

	/**
	 * Unlinks a messaging channel identity from a user.
	 */
	fun unlinkChannelAccount(username: String, channelType: String, channelUserId: String): Boolean {
		val key = "$channelType:$channelUserId"
		val existing = channelLinks[key] ?: return false
		if (existing.username != username) return false
		channelLinks.remove(key)
		logger.info("Channel account unlinked: $channelType:$channelUserId from $username")
		return true
	}

	/**
	 * Resolves a messaging channel identity to a registered username.
	 * Returns null if no link exists.
	 */
	fun resolveChannelUser(channelType: String, channelUserId: String): String? {
		val key = "$channelType:$channelUserId"
		return channelLinks[key]?.username
	}

	/**
	 * Lists all channel links for a given user.
	 */
	fun getChannelLinks(username: String): List<ChannelLinkInfo> {
		return channelLinks.values
			.filter { it.username == username }
			.map { ChannelLinkInfo(it.channelType, it.channelUserId, it.linkedAt) }
	}

	/**
	 * Lists all channel links (admin view).
	 */
	fun getAllChannelLinks(): List<ChannelLinkInfo> {
		return channelLinks.values.map { ChannelLinkInfo(it.channelType, it.channelUserId, it.linkedAt) }
	}

	private fun createSession(username: String, isAdmin: Boolean): DashboardSession {
		val token = generateToken()
		val csrfToken = generateToken()
		val expiresAt = System.currentTimeMillis() + authConfig.sessionExpiryHours * 60 * 60 * 1000L
		val session = DashboardSession(token, csrfToken, username, isAdmin, expiresAt = expiresAt)
		sessions[token] = session
		return session
	}

	private fun cleanupExpiredSessions() {
		val now = System.currentTimeMillis()
		val removed = sessions.entries.count { now > it.value.expiresAt }
		sessions.entries.removeIf { now > it.value.expiresAt }
		if (removed > 0) {
			logger.info("Cleaned up $removed expired sessions")
		}
	}

	private fun generateToken(): String {
		val bytes = ByteArray(32)
		SecureRandom().nextBytes(bytes)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
	}

	private fun generateSignupToken(): String {
		val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
		val random = SecureRandom()
		return (1..10).map { chars[random.nextInt(chars.length)] }.joinToString("")
	}
}
