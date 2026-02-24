package tech.lenooby09.openklaw.llm

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.ProviderConfig
import tech.lenooby09.openklaw.config.ProviderType

data class LlmMessage(val role: String, val content: String)

data class LlmResponse(
	val content: String,
	val model: String,
	val provider: String,
	val tokensUsed: Int = 0,
	val finishReason: String = "stop"
)

interface LlmProvider {
	val name: String
	val isAvailable: Boolean
	suspend fun complete(messages: List<LlmMessage>, model: String): LlmResponse
	suspend fun completeStream(messages: List<LlmMessage>, model: String, onChunk: suspend (String) -> Unit): LlmResponse
	suspend fun checkHealth(): Boolean
}

abstract class BaseLlmProvider(
	override val name: String,
	protected val config: ProviderConfig
) : LlmProvider {
	override var isAvailable: Boolean = config.enabled
		protected set

	protected val apiKey: String = config.resolveApiKey()

	override suspend fun checkHealth(): Boolean {
		return try {
			val response = complete(
				listOf(LlmMessage("user", "ping")),
				config.model.ifEmpty { defaultModel() }
			)
			isAvailable = response.content.isNotEmpty()
			isAvailable
		} catch (e: Exception) {
			isAvailable = false
			false
		}
	}

	protected abstract fun defaultModel(): String

	/**
	 * Resolves the effective base URL for this provider.
	 * When running inside Docker (OPENKLAW_SANDBOXED=true), rewrites localhost
	 * references to host.docker.internal so the container can reach host services.
	 */
	protected fun resolveBaseUrl(): String {
		val url = config.baseUrl.ifEmpty { return "" }
		val sandboxed = System.getenv("OPENKLAW_SANDBOXED")?.equals("true", ignoreCase = true) == true
		if (!sandboxed) return url
		return url
			.replace("://localhost:", "://host.docker.internal:")
			.replace("://localhost/", "://host.docker.internal/")
			.replace("://127.0.0.1:", "://host.docker.internal:")
			.replace("://127.0.0.1/", "://host.docker.internal/")
			.let { if (it.endsWith("://localhost")) it.replace("://localhost", "://host.docker.internal") else it }
			.let { if (it.endsWith("://127.0.0.1")) it.replace("://127.0.0.1", "://host.docker.internal") else it }
	}
}

class OpenAiProvider(config: ProviderConfig) : BaseLlmProvider("OpenAI", config) {
	override fun defaultModel(): String = "gpt-4o"

	override suspend fun complete(messages: List<LlmMessage>, model: String): LlmResponse {
		// Placeholder — will integrate with koog prompt-executor-openai-client
		return LlmResponse(
			content = "[OpenAI provider not yet connected — configure API key]",
			model = model.ifEmpty { defaultModel() },
			provider = name
		)
	}

	override suspend fun completeStream(
		messages: List<LlmMessage>,
		model: String,
		onChunk: suspend (String) -> Unit
	): LlmResponse {
		val response = complete(messages, model)
		onChunk(response.content)
		return response
	}
}

class AnthropicProvider(config: ProviderConfig) : BaseLlmProvider("Anthropic", config) {
	override fun defaultModel(): String = "claude-sonnet-4-20250514"

	override suspend fun complete(messages: List<LlmMessage>, model: String): LlmResponse {
		return LlmResponse(
			content = "[Anthropic provider not yet connected — configure API key]",
			model = model.ifEmpty { defaultModel() },
			provider = name
		)
	}

	override suspend fun completeStream(
		messages: List<LlmMessage>,
		model: String,
		onChunk: suspend (String) -> Unit
	): LlmResponse {
		val response = complete(messages, model)
		onChunk(response.content)
		return response
	}
}

class OllamaProvider(config: ProviderConfig) : BaseLlmProvider("Ollama", config) {
	private val logger = LoggerFactory.getLogger(OllamaProvider::class.java)
	private val client = HttpClient(CIO) {
		engine {
			requestTimeout = 120_000
		}
	}
	private val baseUrl: String = resolveBaseUrl().ifEmpty { "http://localhost:11434" }

	override fun defaultModel(): String = "llama3.2"

	override suspend fun complete(messages: List<LlmMessage>, model: String): LlmResponse {
		val effectiveModel = model.ifEmpty { config.model.ifEmpty { defaultModel() } }
		val messagesArray = buildJsonArray {
			for (msg in messages) {
				addJsonObject {
					put("role", msg.role)
					put("content", msg.content)
				}
			}
		}
		val body = buildJsonObject {
			put("model", effectiveModel)
			put("messages", messagesArray)
			put("stream", false)
		}

		val response = client.post("$baseUrl/api/chat") {
			contentType(ContentType.Application.Json)
			setBody(body.toString())
		}

		if (response.status != HttpStatusCode.OK) {
			val errorBody = response.bodyAsText()
			logger.error("Ollama returned ${response.status}: $errorBody")
			throw RuntimeException("Ollama API error ${response.status}: $errorBody")
		}

		val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
		val content = json["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""
		val tokensUsed = json["eval_count"]?.jsonPrimitive?.intOrNull ?: 0
		val done = json["done"]?.jsonPrimitive?.booleanOrNull ?: true

		return LlmResponse(
			content = content,
			model = effectiveModel,
			provider = name,
			tokensUsed = tokensUsed,
			finishReason = if (done) "stop" else "length"
		)
	}

	override suspend fun completeStream(
		messages: List<LlmMessage>,
		model: String,
		onChunk: suspend (String) -> Unit
	): LlmResponse {
		val effectiveModel = model.ifEmpty { config.model.ifEmpty { defaultModel() } }
		val messagesArray = buildJsonArray {
			for (msg in messages) {
				addJsonObject {
					put("role", msg.role)
					put("content", msg.content)
				}
			}
		}
		val body = buildJsonObject {
			put("model", effectiveModel)
			put("messages", messagesArray)
			put("stream", true)
		}

		val response = client.post("$baseUrl/api/chat") {
			contentType(ContentType.Application.Json)
			setBody(body.toString())
		}

		if (response.status != HttpStatusCode.OK) {
			val errorBody = response.bodyAsText()
			logger.error("Ollama streaming returned ${response.status}: $errorBody")
			throw RuntimeException("Ollama API error ${response.status}: $errorBody")
		}

		val fullContent = StringBuilder()
		var tokensUsed = 0
		val channel = response.bodyAsChannel()
		val buffer = StringBuilder()

		while (!channel.isClosedForRead) {
			val byte = try {
				channel.readByte()
			} catch (_: Exception) {
				break
			}
			val char = byte.toInt().toChar()
			buffer.append(char)
			if (char == '\n' && buffer.isNotBlank()) {
				val line = buffer.toString().trim()
				buffer.clear()
				if (line.isEmpty()) continue
				try {
					val json = Json.parseToJsonElement(line).jsonObject
					val chunk = json["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""
					if (chunk.isNotEmpty()) {
						fullContent.append(chunk)
						onChunk(chunk)
					}
					val done = json["done"]?.jsonPrimitive?.booleanOrNull ?: false
					if (done) {
						tokensUsed = json["eval_count"]?.jsonPrimitive?.intOrNull ?: 0
					}
				} catch (e: Exception) {
					logger.debug("Failed to parse streaming chunk: $line", e)
				}
			}
		}

		return LlmResponse(
			content = fullContent.toString(),
			model = effectiveModel,
			provider = name,
			tokensUsed = tokensUsed,
			finishReason = "stop"
		)
	}

	override suspend fun checkHealth(): Boolean {
		return try {
			val response = client.get("$baseUrl/api/tags")
			isAvailable = response.status == HttpStatusCode.OK
			if (isAvailable) {
				logger.info("Ollama is reachable at $baseUrl")
			}
			isAvailable
		} catch (e: Exception) {
			logger.warn("Ollama health check failed at $baseUrl: ${e.message}")
			isAvailable = false
			false
		}
	}
}

class OpenRouterProvider(config: ProviderConfig) : BaseLlmProvider("OpenRouter", config) {
	override fun defaultModel(): String = "openai/gpt-4o"

	override suspend fun complete(messages: List<LlmMessage>, model: String): LlmResponse {
		return LlmResponse(
			content = "[OpenRouter provider not yet connected — configure API key]",
			model = model.ifEmpty { defaultModel() },
			provider = name
		)
	}

	override suspend fun completeStream(
		messages: List<LlmMessage>,
		model: String,
		onChunk: suspend (String) -> Unit
	): LlmResponse {
		val response = complete(messages, model)
		onChunk(response.content)
		return response
	}
}

fun createProvider(config: ProviderConfig): LlmProvider = when (config.type) {
	ProviderType.OPENAI -> OpenAiProvider(config)
	ProviderType.ANTHROPIC -> AnthropicProvider(config)
	ProviderType.OLLAMA -> OllamaProvider(config)
	ProviderType.OPENROUTER -> OpenRouterProvider(config)
}
