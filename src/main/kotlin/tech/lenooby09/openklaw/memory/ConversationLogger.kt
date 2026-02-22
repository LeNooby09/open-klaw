package tech.lenooby09.openklaw.memory

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.agent.ChatMessage
import tech.lenooby09.openklaw.config.MemoryConfig
import java.io.File
import java.nio.file.attribute.PosixFilePermission
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.nio.file.Files as NioFiles

/**
 * Logs raw conversation transcripts to daily JSONL files.
 * Each session's messages are appended in real time so no data is lost on crash.
 * File layout: {dataDir}/logs/{date}/{sessionId}.jsonl
 */
class ConversationLogger(private val config: MemoryConfig) {
	private val logger = LoggerFactory.getLogger(ConversationLogger::class.java)
	private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
	private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

	companion object {
		private val SAFE_SESSION_ID_REGEX = Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$")
		private val SAFE_DATE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}$")
		private val SENSITIVE_PATTERNS = listOf(
			Regex("(?i)(api[_-]?key|secret|token|password|authorization|bearer)\\s*[=:]\\s*\\S+"),
			Regex("(?i)(sk-[a-zA-Z0-9]{20,})"),
			Regex("(?i)(ghp_[a-zA-Z0-9]{36})"),
			Regex("-----BEGIN (RSA |EC |DSA )?PRIVATE KEY-----")
		)
		private val OWNER_ONLY_PERMS = setOf(
			PosixFilePermission.OWNER_READ,
			PosixFilePermission.OWNER_WRITE
		)

		/** Redacts known sensitive patterns from text. */
		fun redactSensitive(text: String): String {
			var result = text
			for (pattern in SENSITIVE_PATTERNS) {
				result = pattern.replace(result, "[REDACTED]")
			}
			return result
		}
	}

	private fun validateSessionId(sessionId: String) {
		require(SAFE_SESSION_ID_REGEX.matches(sessionId)) { "Invalid session ID format: $sessionId" }
	}

	private fun validateDate(date: String) {
		require(SAFE_DATE_REGEX.matches(date)) { "Invalid date format: $date" }
	}

	private fun setRestrictivePermissions(file: File) {
		try {
			NioFiles.setPosixFilePermissions(file.toPath(), OWNER_ONLY_PERMS)
		} catch (_: UnsupportedOperationException) {
			file.setReadable(false, false)
			file.setWritable(false, false)
			file.setReadable(true, true)
			file.setWritable(true, true)
		} catch (e: Exception) {
			logger.warn("Could not set restrictive permissions on ${file.name}: ${e.message}")
		}
	}

	private val logsDir: File
		get() = File(config.dataDir, "logs")

	/**
	 * Appends a single message to the session's daily JSONL log file.
	 */
	fun logMessage(sessionId: String, username: String, message: ChatMessage) {
		if (!config.conversationLoggingEnabled) return

		try {
			val dateDir = File(logsDir, LocalDate.now().format(dateFormat))
			dateDir.mkdirs()

			validateSessionId(sessionId)
			val logFile = File(dateDir, "$sessionId.jsonl")

			val entry = json.encodeToString(
				buildJsonObject {
					put("sessionId", sessionId)
					put("username", username)
					put("messageId", message.id)
					put("role", message.role)
					put("content", message.content)
					put("timestamp", message.timestamp)
					if (message.model.isNotEmpty()) put("model", message.model)
					if (message.provider.isNotEmpty()) put("provider", message.provider)
					if (message.toolCall != null) {
						put("toolCall", buildJsonObject {
							put("toolName", message.toolCall.toolName)
							put("arguments", json.encodeToJsonElement(message.toolCall.arguments))
							if (message.toolCall.result != null) {
								put("result", buildJsonObject {
									put("success", message.toolCall.result.success)
									put("output", redactSensitive(message.toolCall.result.output.take(5000)))
									message.toolCall.result.error?.let { put("error", redactSensitive(it)) }
								})
							}
						})
					}
				}
			)

			logFile.appendText(entry + "\n")
			setRestrictivePermissions(logFile)
		} catch (e: Exception) {
			logger.error("Failed to log message for session $sessionId: ${e.message}", e)
		}
	}

	/**
	 * Returns all daily log directories sorted by date (oldest first).
	 */
	fun listLogDates(): List<String> {
		val dir = logsDir
		if (!dir.exists()) return emptyList()
		return dir.listFiles()
			?.filter { it.isDirectory }
			?.map { it.name }
			?.sorted()
			?: emptyList()
	}

	/**
	 * Returns all session log files for a given date.
	 */
	fun listSessionLogs(date: String): List<String> {
		validateDate(date)
		val dateDir = File(logsDir, date)
		if (!dateDir.exists()) return emptyList()
		return dateDir.listFiles()
			?.filter { it.extension == "jsonl" }
			?.map { it.nameWithoutExtension }
			?: emptyList()
	}

	/**
	 * Reads all messages from a specific session log file.
	 */
	fun readSessionLog(date: String, sessionId: String): List<JsonObject> {
		validateDate(date)
		validateSessionId(sessionId)
		val file = File(File(logsDir, date), "$sessionId.jsonl")
		if (!file.exists()) return emptyList()

		return file.readLines()
			.filter { it.isNotBlank() }
			.mapNotNull { line ->
				try {
					json.parseToJsonElement(line).jsonObject
				} catch (e: Exception) {
					logger.warn("Failed to parse log line: ${e.message}")
					null
				}
			}
	}

	/**
	 * Reads all messages across all sessions for a given date.
	 */
	fun readDailyLogs(date: String): List<JsonObject> {
		validateDate(date)
		val dateDir = File(logsDir, date)
		if (!dateDir.exists()) return emptyList()

		return dateDir.listFiles()
			?.filter { it.extension == "jsonl" }
			?.flatMap { file ->
				file.readLines()
					.filter { it.isNotBlank() }
					.mapNotNull { line ->
						try {
							json.parseToJsonElement(line).jsonObject
						} catch (e: Exception) {
							null
						}
					}
			}
			?.sortedBy { it["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L }
			?: emptyList()
	}

	/**
	 * Returns all log entries across all dates, as pairs of (content, metadata).
	 * Used by semantic search for indexing.
	 */
	fun readAllLogs(): List<Pair<String, JsonObject>> {
		val results = mutableListOf<Pair<String, JsonObject>>()
		for (date in listLogDates()) {
			for (entry in readDailyLogs(date)) {
				val content = entry["content"]?.jsonPrimitive?.contentOrNull ?: continue
				results.add(content to entry)
			}
		}
		return results
	}
}
