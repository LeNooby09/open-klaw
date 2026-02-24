package tech.lenooby09.openklaw.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.llm.LlmMessage
import tech.lenooby09.openklaw.llm.LlmOrchestrator
import tech.lenooby09.openklaw.memory.MemoryManager
import tech.lenooby09.openklaw.skills.SkillManager
import tech.lenooby09.openklaw.tools.ToolExecutionRequest
import tech.lenooby09.openklaw.tools.ToolRegistry
import tech.lenooby09.openklaw.tools.ToolResult
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ChatMessage(
	val id: String = UUID.randomUUID().toString(),
	val role: String,
	val content: String,
	val timestamp: Long = System.currentTimeMillis(),
	val model: String = "",
	val provider: String = "",
	val toolCall: ToolCallInfo? = null
)

@Serializable
data class ToolCallInfo(
	val toolName: String,
	val arguments: Map<String, String> = emptyMap(),
	val result: ToolResult? = null
)

@Serializable
data class ConversationSession(
	val id: String = UUID.randomUUID().toString(),
	val username: String,
	val createdAt: Long = System.currentTimeMillis(),
	var lastActiveAt: Long = System.currentTimeMillis(),
	val messages: MutableList<ChatMessage> = mutableListOf()
)

@Serializable
data class ChatRequest(val message: String, val sessionId: String = "")

@Serializable
data class ChatResponse(
	val sessionId: String,
	val message: ChatMessage,
	val done: Boolean = true
)

@Serializable
data class ConversationListItem(
	val id: String,
	val username: String,
	val messageCount: Int,
	val createdAt: Long,
	val lastActiveAt: Long
)

class AgentLoop(
	private val orchestrator: LlmOrchestrator,
	private val toolRegistry: ToolRegistry? = null,
	private val memoryManager: MemoryManager? = null,
	private val skillManager: SkillManager? = null,
	private val conversationsDir: File = File("data/conversations")
) {
	private val logger = LoggerFactory.getLogger(AgentLoop::class.java)
	private val conversations = ConcurrentHashMap<String, ConversationSession>()
	private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
	val laneQueue = LaneQueue()

	init {
		conversationsDir.mkdirs()
	}

	companion object {
		const val MAX_TOOL_ITERATIONS = 5
		private val TOOL_BLOCK_REGEX = Regex("```tool\\s*\\n(\\{[\\s\\S]*?})\\s*\\n```")
		private val SAFE_UUID_REGEX = Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$")

		fun isValidSessionId(id: String): Boolean = SAFE_UUID_REGEX.matches(id)
	}

	private fun buildSystemPrompt(username: String, userQuery: String = "", allowedTools: Set<String>? = null): String {
		val base = """
			You are Open-Klaw, an AI assistant running as a local agent. You are helpful, concise, and 
			thoughtful. You can assist with coding, analysis, writing, and general questions.
			When you don't know something, say so honestly.
		""".trimIndent()

		val toolSection = if (allowedTools != null) {
			toolRegistry?.buildToolDescriptions(allowedTools) ?: ""
		} else {
			toolRegistry?.buildToolDescriptions() ?: ""
		}
		val memoryContext = memoryManager?.buildMemoryContext(username) ?: ""
		val relevantMemory = if (userQuery.isNotEmpty()) {
			memoryManager?.searchRelevantMemory(userQuery) ?: ""
		} else ""

		val skillsContext = skillManager?.buildSkillsContext() ?: ""

		return base + toolSection + memoryContext + relevantMemory + skillsContext
	}

	/**
	 * @param allowedTools If non-null, only these tool names may be executed. Others are blocked.
	 *                     Pass null (default) for unrestricted tool access.
	 */
	suspend fun chat(username: String, request: ChatRequest, allowedTools: Set<String>? = null): ChatResponse {
		val session = resolveSession(username, request.sessionId)

		return laneQueue.withLane(session.id) {
			val userMessage = ChatMessage(role = "user", content = request.message)
			session.messages.add(userMessage)
			session.lastActiveAt = System.currentTimeMillis()
			memoryManager?.logMessage(session.id, username, userMessage)

			logger.info("Agent thinking for user=$username session=${session.id}")

			val assistantMessage = runAgentLoop(session, allowedTools)
			memoryManager?.logMessage(session.id, username, assistantMessage)

			ChatResponse(
				sessionId = session.id,
				message = assistantMessage
			)
		}
	}

	suspend fun chatStream(
		username: String,
		request: ChatRequest,
		onChunk: suspend (String) -> Unit,
		allowedTools: Set<String>? = null
	): ChatResponse {
		val session = resolveSession(username, request.sessionId)

 	return laneQueue.withLane(session.id) {
 			val userMessage = ChatMessage(role = "user", content = request.message)
 			session.messages.add(userMessage)
 			session.lastActiveAt = System.currentTimeMillis()
 			memoryManager?.logMessage(session.id, username, userMessage)

 			logger.info("Agent streaming for user=$username session=${session.id}")

 			val llmMessages = buildLlmMessages(session, request.message, allowedTools)
 			val llmResponse = orchestrator.completeStream(llmMessages, onChunk)

 			var content = llmResponse.content
 			var model = llmResponse.model
 			var provider = llmResponse.provider

 			// Handle tool calls in streaming mode (execute tools after initial stream completes)
 			if (toolRegistry != null) {
 				var iteration = 0
 				while (iteration < MAX_TOOL_ITERATIONS) {
  				val toolCall = parseToolCall(content) ?: break
  				iteration++

  				// Enforce tool restrictions if allowedTools is specified
  				if (allowedTools != null && toolCall.toolName !in allowedTools) {
  					logger.warn("Tool '${toolCall.toolName}' blocked by tool restrictions in streaming mode")
  					val blockedResult = ChatMessage(
  						role = "tool",
  						content = "Tool '${toolCall.toolName}' is not permitted in this context. Allowed tools: ${allowedTools.joinToString(", ")}"
  					)
  					session.messages.add(ChatMessage(role = "assistant", content = content, model = model, provider = provider))
  					session.messages.add(blockedResult)
  					onChunk("\n\n🔧 **Tool: ${toolCall.toolName}** → ⛔ Blocked by permissions\n")
  					val followUpMessages = buildLlmMessages(session, allowedTools = allowedTools)
  					val followUp = orchestrator.completeStream(followUpMessages, onChunk)
  					content = followUp.content
  					model = followUp.model
  					provider = followUp.provider
  					continue
  				}

  				val result = toolRegistry.execute(toolCall)
 					val toolMessage = ChatMessage(
 						role = "assistant",
 						content = content,
 						model = model,
 						provider = provider,
 						toolCall = ToolCallInfo(toolCall.toolName, toolCall.arguments, result)
 					)
 					session.messages.add(toolMessage)

 					val toolResultMessage = ChatMessage(role = "tool", content = formatToolResult(result))
 					session.messages.add(toolResultMessage)

 					onChunk("\n\n🔧 **Tool: ${toolCall.toolName}** → ${if (result.success) "✅" else "❌"}\n")
 					if (result.output.isNotEmpty()) {
 						onChunk("```\n${result.output.take(2000)}\n```\n")
 					}
 					if (result.error != null) {
 						onChunk("Error: ${result.error}\n")
 					}

 					val followUpMessages = buildLlmMessages(session, allowedTools = allowedTools)
 					val followUp = orchestrator.completeStream(followUpMessages, onChunk)
 					content = followUp.content
 					model = followUp.model
 					provider = followUp.provider
 				}
 			}

 			val assistantMessage = ChatMessage(
 				role = "assistant",
 				content = content,
 				model = model,
 				provider = provider
 			)
 			session.messages.add(assistantMessage)
 			session.lastActiveAt = System.currentTimeMillis()
 			memoryManager?.logMessage(session.id, username, assistantMessage)

 			ChatResponse(
 				sessionId = session.id,
 				message = assistantMessage
 			)
 		}
 	}

	fun getConversation(sessionId: String): ConversationSession? = conversations[sessionId]

	fun listConversations(): List<ConversationListItem> =
		conversations.values.map {
			ConversationListItem(
				id = it.id,
				username = it.username,
				messageCount = it.messages.size,
				createdAt = it.createdAt,
				lastActiveAt = it.lastActiveAt
			)
		}.sortedByDescending { it.lastActiveAt }

	fun getActiveConversationCount(): Int = conversations.size

	fun deleteConversation(sessionId: String, requestingUsername: String, isAdmin: Boolean): Boolean {
		val conversation = conversations[sessionId]
		if (conversation != null) {
 		if (!isAdmin && conversation.username != requestingUsername) return false
			conversations.remove(sessionId)
			laneQueue.removeLane(sessionId)
			return true
		}
		// Check if it exists on disk — validate format before using in file path
		if (!isValidSessionId(sessionId)) return false
		val file = File(conversationsDir, "$sessionId.jsonl")
		if (file.exists()) {
			if (!isAdmin) {
				// Cannot verify ownership of archived conversation without loading — require admin
				return false
			}
			file.delete()
			return true
		}
		return false
	}

	/**
	 * Flushes idle conversations to disk as JSONL files and removes them from memory.
	 * A conversation is considered idle if lastActiveAt is older than the given timeout.
	 */
	fun flushIdleConversations(idleTimeoutMinutes: Int) {
		val cutoff = System.currentTimeMillis() - idleTimeoutMinutes * 60 * 1000L
		val toFlush = conversations.entries.filter { it.value.lastActiveAt < cutoff }
		for (entry in toFlush) {
			try {
				val session = entry.value
				memoryManager?.distillConversation(session.id, session.username, session.messages)
				if (!isValidSessionId(session.id)) {
					logger.warn("Skipping flush for conversation with invalid ID: ${session.id}")
					continue
				}
				val file = File(conversationsDir, "${session.id}.jsonl")
				file.bufferedWriter().use { writer ->
					// First line: session metadata
					val meta = json.encodeToString(
						buildJsonObject {
							put("id", session.id)
							put("username", session.username)
							put("createdAt", session.createdAt)
							put("lastActiveAt", session.lastActiveAt)
							put("messageCount", session.messages.size)
						}
					)
					writer.write(meta)
					writer.newLine()
					// Subsequent lines: one message per line
					for (msg in session.messages) {
						writer.write(json.encodeToString(msg))
						writer.newLine()
					}
				}
 			conversations.remove(entry.key)
				laneQueue.removeLane(entry.key)
				logger.info("Flushed idle conversation ${session.id} (user=${session.username}, messages=${session.messages.size}) to disk")
			} catch (e: Exception) {
				logger.error("Failed to flush conversation ${entry.key} to disk: ${e.message}", e)
			}
		}
		if (toFlush.isNotEmpty()) {
			logger.info("Flushed ${toFlush.size} idle conversation(s) to disk")
		}
	}

	private fun resolveSession(username: String, sessionId: String): ConversationSession {
		return if (sessionId.isNotEmpty()) {
			val existing = conversations[sessionId]
			if (existing != null && existing.username != username) {
				createSession(username)
			} else {
				existing ?: createSession(username)
			}
		} else {
			createSession(username)
		}
	}

	private suspend fun runAgentLoop(session: ConversationSession, allowedTools: Set<String>? = null): ChatMessage {
		var iteration = 0

		while (iteration <= MAX_TOOL_ITERATIONS) {
			val llmMessages = buildLlmMessages(session, allowedTools = allowedTools)
			val llmResponse = orchestrator.completeStream(llmMessages) { /* collect internally */ }

			if (toolRegistry == null) {
				val msg = ChatMessage(
					role = "assistant",
					content = llmResponse.content,
					model = llmResponse.model,
					provider = llmResponse.provider
				)
				session.messages.add(msg)
				session.lastActiveAt = System.currentTimeMillis()
				return msg
			}

			val toolCall = parseToolCall(llmResponse.content)

			if (toolCall == null) {
				val msg = ChatMessage(
					role = "assistant",
					content = llmResponse.content,
					model = llmResponse.model,
					provider = llmResponse.provider
				)
				session.messages.add(msg)
				session.lastActiveAt = System.currentTimeMillis()
				return msg
			}

			// Enforce tool restrictions if allowedTools is specified
			if (allowedTools != null && toolCall.toolName !in allowedTools) {
				logger.warn("Tool '${toolCall.toolName}' blocked by scheduler tool restrictions")
				val blockedResult = ChatMessage(
					role = "tool",
					content = "Tool '${toolCall.toolName}' is not permitted in this automated context. Allowed tools: ${allowedTools.joinToString(", ")}"
				)
				session.messages.add(ChatMessage(role = "assistant", content = llmResponse.content, model = llmResponse.model, provider = llmResponse.provider))
				session.messages.add(blockedResult)
				iteration++
				continue
			}

			iteration++
			logger.info("Tool call detected: ${toolCall.toolName} (iteration $iteration)")

			val result = toolRegistry.execute(toolCall)

			val toolMessage = ChatMessage(
				role = "assistant",
				content = llmResponse.content,
				model = llmResponse.model,
				provider = llmResponse.provider,
				toolCall = ToolCallInfo(toolCall.toolName, toolCall.arguments, result)
			)
			session.messages.add(toolMessage)

			val toolResultMessage = ChatMessage(role = "tool", content = formatToolResult(result))
			session.messages.add(toolResultMessage)
		}

		val maxIterMsg = ChatMessage(
			role = "assistant",
			content = "I've reached the maximum number of tool calls ($MAX_TOOL_ITERATIONS) for this turn. Please let me know if you'd like me to continue."
		)
		session.messages.add(maxIterMsg)
		session.lastActiveAt = System.currentTimeMillis()
		return maxIterMsg
	}

	internal fun parseToolCall(content: String): ToolExecutionRequest? {
		val match = TOOL_BLOCK_REGEX.find(content) ?: return null
		val jsonStr = match.groupValues[1].trim()

		return try {
			val jsonObj = json.parseToJsonElement(jsonStr).jsonObject
			val toolName = jsonObj["tool"]?.jsonPrimitive?.content ?: return null
			val arguments = mutableMapOf<String, String>()

			jsonObj["arguments"]?.jsonObject?.forEach { (key, value) ->
				arguments[key] = when (value) {
					is JsonPrimitive -> value.content
					else -> value.toString()
				}
			}

			ToolExecutionRequest(toolName, arguments)
		} catch (e: Exception) {
			logger.warn("Failed to parse tool call from LLM response: ${e.message}")
			null
		}
	}

	private fun formatToolResult(result: ToolResult): String {
		return buildString {
			appendLine("Tool: ${result.toolName}")
			appendLine("Status: ${if (result.success) "success" else "failed"}")
			if (result.output.isNotEmpty()) {
				appendLine("Output:")
				appendLine(result.output.take(10_000))
			}
			if (result.error != null) {
				appendLine("Error: ${result.error}")
			}
			if (result.metadata.isNotEmpty()) {
				appendLine("Metadata: ${result.metadata}")
			}
			appendLine("Execution time: ${result.executionTimeMs}ms")
		}
	}

	private fun createSession(username: String): ConversationSession {
		val session = ConversationSession(username = username)
		conversations[session.id] = session
		return session
	}

	private fun buildLlmMessages(session: ConversationSession, userQuery: String = "", allowedTools: Set<String>? = null): List<LlmMessage> {
		val prompt = buildSystemPrompt(session.username, userQuery, allowedTools)
		val messages = mutableListOf(LlmMessage("system", prompt))
		val history = session.messages.takeLast(50)
		history.forEach {
			val role = if (it.role == "tool") "user" else it.role
			messages.add(LlmMessage(role, it.content))
		}
		return messages
	}
}
