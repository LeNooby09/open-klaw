package tech.lenooby09.openklaw.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap

/**
 * Fine-Grained User Permissions — per-user tool access controls for interactive sessions.
 *
 * Admins can configure which tools each user is allowed to use. By default, users have
 * access to all tools (unrestricted). Admins always have unrestricted access.
 *
 * Permissions are persisted to a JSON file so they survive application restarts.
 *
 * Permission model:
 * - If a user has no explicit permissions entry, they get default access (all tools).
 * - If a user has an explicit allowedTools set, only those tools are available.
 * - Admins bypass all permission checks.
 */
class UserPermissionManager(
	private val dataDir: String = "data"
) {
	private val logger = LoggerFactory.getLogger(UserPermissionManager::class.java)
	private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
	private val permissionsFile = File(dataDir, "permissions.json")

	// username -> set of allowed tool names (null = unrestricted)
	private val userPermissions = ConcurrentHashMap<String, UserToolPermissions>()

	init {
		loadPermissions()
	}

	/**
	 * Set the allowed tools for a user. Pass null to grant unrestricted access.
	 */
	fun setUserTools(username: String, allowedTools: Set<String>?) {
		if (allowedTools == null) {
			userPermissions.remove(username)
			logger.info("Cleared tool restrictions for user=$username (unrestricted)")
		} else {
			userPermissions[username] = UserToolPermissions(username, allowedTools)
			logger.info("Set tool permissions for user=$username: ${allowedTools.sorted()}")
		}
		savePermissions()
	}

	/**
	 * Get the allowed tools for a user. Returns null if the user has unrestricted access.
	 */
	fun getUserTools(username: String): Set<String>? {
		return userPermissions[username]?.allowedTools
	}

	/**
	 * Resolve the effective allowed tools for a user in an interactive session.
	 * @param username The user making the request
	 * @param isAdmin Whether the user is an admin (admins bypass restrictions)
	 * @return null for unrestricted access, or a Set of allowed tool names
	 */
	fun resolveAllowedTools(username: String, isAdmin: Boolean): Set<String>? {
		if (isAdmin) return null // Admins always have unrestricted access
		return userPermissions[username]?.allowedTools
	}

	/**
	 * Check if a specific tool is allowed for a user.
	 */
	fun isToolAllowed(username: String, isAdmin: Boolean, toolName: String): Boolean {
		if (isAdmin) return true
		val perms = userPermissions[username] ?: return true // No restrictions = all allowed
		return toolName in perms.allowedTools
	}

	/**
	 * Get all user permission entries.
	 */
	fun listPermissions(): List<UserToolPermissions> {
		return userPermissions.values.toList().sortedBy { it.username }
	}

	/**
	 * Remove permission entry for a user (reverts to unrestricted).
	 */
	fun removeUserPermissions(username: String): Boolean {
		val removed = userPermissions.remove(username) != null
		if (removed) {
			logger.info("Removed tool permissions for user=$username (reverted to unrestricted)")
			savePermissions()
		}
		return removed
	}

	/**
	 * Get the number of users with explicit permission entries.
	 */
	fun getPermissionCount(): Int = userPermissions.size

	/**
	 * Persist permissions to disk as JSON with owner-only POSIX permissions.
	 */
	private fun savePermissions() {
		try {
			permissionsFile.parentFile?.mkdirs()
			val entries = userPermissions.values.toList().sortedBy { it.username }
			permissionsFile.writeText(json.encodeToString(entries))
			try {
				Files.setPosixFilePermissions(permissionsFile.toPath(), PosixFilePermissions.fromString("rw-------"))
			} catch (_: UnsupportedOperationException) {
				// Non-POSIX filesystem (e.g. Windows) — skip
			}
		} catch (e: Exception) {
			logger.error("Failed to save permissions: ${e.message}", e)
		}
	}

	/**
	 * Load persisted permissions from disk on startup.
	 */
	private fun loadPermissions() {
		if (!permissionsFile.exists()) return
		try {
			val entries: List<UserToolPermissions> = json.decodeFromString(permissionsFile.readText())
			for (entry in entries) {
				userPermissions[entry.username] = entry
			}
			logger.info("Loaded ${entries.size} user permission entries from ${permissionsFile.path}")
		} catch (e: Exception) {
			logger.error("Failed to load permissions from ${permissionsFile.path}: ${e.message}", e)
		}
	}
}

@Serializable
data class UserToolPermissions(
	val username: String,
	val allowedTools: Set<String>
)
