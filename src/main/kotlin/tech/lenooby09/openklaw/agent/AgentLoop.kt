package tech.lenooby09.openklaw.agent

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.llm.LlmMessage
import tech.lenooby09.openklaw.llm.LlmOrchestrator
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ChatMessage(
	val id: String = UUID.randomUUID().toString(),
	val role: String,
	val content: String,
	val timestamp: Long = System.currentTimeMillis(),
	val model: String = "",
	val provider: String = ""
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

class AgentLoop(private val orchestrator: LlmOrchestrator) {
	private val logger = LoggerFactory.getLogger(AgentLoop::class.java)
	private val conversations = ConcurrentHashMap<String, ConversationSession>()

	private val systemPrompt = """
        You are Open-Klaw, an AI assistant running as a local agent. You are helpful, concise, and 
        thoughtful. You can assist with coding, analysis, writing, and general questions.
        When you don't know something, say so honestly.
    """.trimIndent()

	suspend fun chat(username: String, request: ChatRequest): ChatResponse {
		val session = if (request.sessionId.isNotEmpty()) {
			val existing = conversations[request.sessionId]
			if (existing != null && existing.username != username) {
				createSession(username)
			} else {
				existing ?: createSession(username)
			}
		} else {
			createSession(username)
		}

		val userMessage = ChatMessage(role = "user", content = request.message)
		session.messages.add(userMessage)
		session.lastActiveAt = System.currentTimeMillis()

		logger.info("Agent thinking for user=$username session=${session.id}")

		val llmMessages = buildLlmMessages(session)
		val llmResponse = orchestrator.complete(llmMessages)

		val assistantMessage = ChatMessage(
			role = "assistant",
			content = llmResponse.content,
			model = llmResponse.model,
			provider = llmResponse.provider
		)
		session.messages.add(assistantMessage)
		session.lastActiveAt = System.currentTimeMillis()

		return ChatResponse(
			sessionId = session.id,
			message = assistantMessage
		)
	}

	suspend fun chatStream(
		username: String,
		request: ChatRequest,
		onChunk: suspend (String) -> Unit
	): ChatResponse {
		val session = if (request.sessionId.isNotEmpty()) {
			val existing = conversations[request.sessionId]
			if (existing != null && existing.username != username) {
				createSession(username)
			} else {
				existing ?: createSession(username)
			}
		} else {
			createSession(username)
		}

		val userMessage = ChatMessage(role = "user", content = request.message)
		session.messages.add(userMessage)
		session.lastActiveAt = System.currentTimeMillis()

		logger.info("Agent streaming for user=$username session=${session.id}")

		val llmMessages = buildLlmMessages(session)
		val llmResponse = orchestrator.completeStream(llmMessages, onChunk)

		val assistantMessage = ChatMessage(
			role = "assistant",
			content = llmResponse.content,
			model = llmResponse.model,
			provider = llmResponse.provider
		)
		session.messages.add(assistantMessage)
		session.lastActiveAt = System.currentTimeMillis()

		return ChatResponse(
			sessionId = session.id,
			message = assistantMessage
		)
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

	fun deleteConversation(sessionId: String): Boolean = conversations.remove(sessionId) != null

	private fun createSession(username: String): ConversationSession {
		val session = ConversationSession(username = username)
		conversations[session.id] = session
		return session
	}

	private fun buildLlmMessages(session: ConversationSession): List<LlmMessage> {
		val messages = mutableListOf(LlmMessage("system", systemPrompt))
		val history = session.messages.takeLast(50)
		history.forEach { messages.add(LlmMessage(it.role, it.content)) }
		return messages
	}
}
