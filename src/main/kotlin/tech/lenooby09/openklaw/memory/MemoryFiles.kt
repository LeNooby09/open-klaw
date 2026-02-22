package tech.lenooby09.openklaw.memory

import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.MemoryConfig
import java.io.File
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.Files as NioFiles

/**
 * Manages the persistent Markdown files that form the agent's long-term memory:
 * - SOUL.md   — identity, personality, tone, and behavioral guidelines
 * - MEMORY.md — curated facts distilled from conversations
 * - USER_{username}.md — per-user preferences, context, and coding style
 */
class MemoryFiles(private val config: MemoryConfig) {
	private val logger = LoggerFactory.getLogger(MemoryFiles::class.java)

	private val dataDir: File
		get() = File(config.dataDir)

	companion object {
		private val SAFE_USERNAME_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
		private val OWNER_ONLY_PERMS = setOf(
			PosixFilePermission.OWNER_READ,
			PosixFilePermission.OWNER_WRITE
		)

		val DEFAULT_SOUL = """
			|# SOUL \u2014 Agent Identity & Personality
			|
			|## Name
			|Open-Klaw
			|
			|## Personality
			|- Helpful, concise, and thoughtful
			|- Honest about limitations \u2014 says \"I don't know\" when uncertain
			|- Adapts tone to the user's style (technical, casual, formal)
			|- Proactive: suggests next steps and related ideas
			|
			|## Behavioral Guidelines
			|- Prioritize correctness over speed
			|- Ask clarifying questions rather than making assumptions
			|- Respect user privacy \u2014 don't store sensitive data unless asked
			|- When using tools, explain what you're doing and why
			|- Keep responses focused and avoid unnecessary verbosity
			|
			|## Communication Style
			|- Use Markdown formatting for code, lists, and structure
			|- Provide examples when explaining concepts
			|- Break complex tasks into clear steps
		""".trimMargin()

		val DEFAULT_MEMORY = """
			|# MEMORY \u2014 Curated Long-Term Memory
			|
			|> This file contains key facts, preferences, and context distilled from conversations.
			|> It is human-readable and editable. The agent reads it at the start of each session.
			|
			|## Key Facts
			|
			|## Learned Preferences
			|
			|## Project Context
		""".trimMargin()

		fun defaultUserProfile(username: String) = """
			|# USER \u2014 $username
			|
			|> Personal profile for $username. Contains preferences, coding style, and context.
			|
			|## Preferences
			|
			|## Coding Style
			|
			|## Personal Context
		""".trimMargin()
	}

	// --- SOUL.md ---

	private val soulFile: File
		get() = File(dataDir, config.soulFile)

	fun readSoul(): String {
		return readFileOrDefault(soulFile, DEFAULT_SOUL)
	}

	fun writeSoul(content: String): Boolean {
		return writeFileSafe(soulFile, content)
	}

	// --- MEMORY.md ---

	private val memoryFile: File
		get() = File(dataDir, config.memoryFile)

	fun readMemory(): String {
		return readFileOrDefault(memoryFile, DEFAULT_MEMORY)
	}

	fun writeMemory(content: String): Boolean {
		return writeFileSafe(memoryFile, content)
	}

	fun appendMemory(entry: String): Boolean {
		if (!config.enabled) return false
		try {
			val file = memoryFile
			file.parentFile?.mkdirs()

			if (file.exists() && file.length() > config.maxMemoryFileSize) {
				logger.warn("MEMORY.md exceeds max size (${config.maxMemoryFileSize} bytes), skipping append")
				return false
			}

			if (!file.exists()) {
				file.writeText(DEFAULT_MEMORY + "\n")
			}

			file.appendText("\n$entry\n")
			return true
		} catch (e: Exception) {
			logger.error("Failed to append to MEMORY.md: ${e.message}", e)
			return false
		}
	}

	// --- USER_{username}.md ---

	/**
	 * Returns the user profile file after validating the username and canonicalizing the path.
	 * @throws IllegalArgumentException if the username is invalid or the resolved path escapes dataDir.
	 */
	private fun userFile(username: String): File {
		require(SAFE_USERNAME_REGEX.matches(username)) { "Invalid username format: $username" }
		val fileName = config.userFilePattern.replace("{username}", username)
		val resolved = File(dataDir, fileName)
		val canonical = resolved.canonicalFile
		val basePath = dataDir.canonicalPath
		require(canonical.path.startsWith(basePath)) {
			"Path traversal detected for username: $username"
		}
		return resolved
	}

	fun readUserProfile(username: String): String {
		return readFileOrDefault(userFile(username), defaultUserProfile(username))
	}

	fun writeUserProfile(username: String, content: String): Boolean {
		return writeFileSafe(userFile(username), content)
	}

	/** Per-user storage budgets in bytes. Configured by admins. */
	private val userStorageBudgets = java.util.concurrent.ConcurrentHashMap<String, Long>()

	/** Returns the storage budget for a user (falls back to config default). */
	fun getUserStorageBudget(username: String): Long {
		return userStorageBudgets[username] ?: config.defaultUserStorageBudget
	}

	/** Sets a per-user storage budget (admin operation). */
	fun setUserStorageBudget(username: String, budgetBytes: Long) {
		userStorageBudgets[username] = budgetBytes
	}

	/** Returns all per-user storage budgets (for admin dashboard). */
	fun getAllStorageBudgets(): Map<String, Long> = userStorageBudgets.toMap()

	/** Returns the current size of a user's profile file. */
	fun getUserProfileSize(username: String): Long {
		return try {
			val file = userFile(username)
			if (file.exists()) file.length() else 0L
		} catch (e: Exception) { 0L }
	}

	fun appendUserProfile(username: String, entry: String): Boolean {
		if (!config.enabled) return false
		try {
			val file = userFile(username)
			file.parentFile?.mkdirs()

			val budget = getUserStorageBudget(username)
			if (file.exists() && file.length() > budget) {
				logger.warn("User profile for $username exceeds storage budget (${budget} bytes), skipping append")
				return false
			}

			if (!file.exists()) {
				file.writeText(defaultUserProfile(username) + "\n")
			}

			file.appendText("\n$entry\n")
			setRestrictivePermissions(file)
			return true
		} catch (e: Exception) {
			logger.error("Failed to append to user profile for $username: ${e.message}", e)
			return false
		}
	}

	fun userProfileExists(username: String): Boolean = userFile(username).exists()

	// --- Helpers ---

	private fun readFileOrDefault(file: File, default: String): String {
		return try {
			if (file.exists()) file.readText() else default
		} catch (e: Exception) {
			logger.error("Failed to read ${file.name}: ${e.message}", e)
			default
		}
	}

	private fun writeFileSafe(file: File, content: String): Boolean {
		return try {
			file.parentFile?.mkdirs()
			file.writeText(content)
			setRestrictivePermissions(file)
			true
		} catch (e: Exception) {
			logger.error("Failed to write ${file.name}: ${e.message}", e)
			false
		}
	}

	/** Sets owner-only read/write permissions on a file (best-effort on non-POSIX systems). */
	private fun setRestrictivePermissions(file: File) {
		try {
			NioFiles.setPosixFilePermissions(file.toPath(), OWNER_ONLY_PERMS)
		} catch (_: UnsupportedOperationException) {
			// Non-POSIX filesystem (e.g. Windows) — best effort
			file.setReadable(false, false)
			file.setWritable(false, false)
			file.setReadable(true, true)
			file.setWritable(true, true)
		} catch (e: Exception) {
			logger.warn("Could not set restrictive permissions on ${file.name}: ${e.message}")
		}
	}

	/**
	 * Ensures all memory files exist with defaults if they haven't been created yet.
	 */
	fun initialize() {
		if (!config.enabled) return
		dataDir.mkdirs()

		if (!soulFile.exists()) {
			writeFileSafe(soulFile, DEFAULT_SOUL)
			logger.info("Created default ${config.soulFile}")
		}
		if (!memoryFile.exists()) {
			writeFileSafe(memoryFile, DEFAULT_MEMORY)
			logger.info("Created default ${config.memoryFile}")
		}
	}

}
