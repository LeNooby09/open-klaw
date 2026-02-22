package tech.lenooby09.openklaw.llm

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
	override fun defaultModel(): String = "llama3.2"

	override suspend fun complete(messages: List<LlmMessage>, model: String): LlmResponse {
		return LlmResponse(
			content = "[Ollama provider not yet connected — ensure Ollama is running]",
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
