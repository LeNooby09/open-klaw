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
		// With host networking (network_mode: host), localhost inside the container IS the host's
		// localhost, so no URL rewriting is needed. Only rewrite when using bridge networking.
		val hostNetwork = System.getenv("OPENKLAW_HOST_NETWORK")?.equals("true", ignoreCase = true) == true
		if (!sandboxed || hostNetwork) return url
		return url
			.replace("://localhost:", "://host.docker.internal:")
			.replace("://localhost/", "://host.docker.internal/")
			.replace("://127.0.0.1:", "://host.docker.internal:")
			.replace("://127.0.0.1/", "://host.docker.internal/")
			.let { if (it.endsWith("://localhost")) it.replace("://localhost", "://host.docker.internal") else it }
			.let { if (it.endsWith("://127.0.0.1")) it.replace("://127.0.0.1", "://host.docker.internal") else it }
	}
}

class OllamaProvider(config: ProviderConfig) : BaseLlmProvider("Ollama", config) {
	private val logger = LoggerFactory.getLogger(OllamaProvider::class.java)
	private val client = HttpClient(CIO) {
		engine {
			requestTimeout = 0 // no global timeout — pulls can take a very long time
			endpoint {
				connectTimeout = 30_000
				socketTimeout = 600_000 // 10 min for large model pulls
				connectAttempts = 3
			}
		}
	}
	private val baseUrl: String = resolveBaseUrl().ifEmpty { "http://localhost:11434" }

	// Track models we've already verified/pulled this session to avoid repeated checks
	private val verifiedModels = mutableSetOf<String>()

	override fun defaultModel(): String = "llama3.2"

	override suspend fun complete(messages: List<LlmMessage>, model: String): LlmResponse {
		val effectiveModel = model.ifEmpty { config.model.ifEmpty { defaultModel() } }
		ensureModelAvailable(effectiveModel) { msg -> logger.info("Pull progress: ${msg.trim()}") }
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
		ensureModelAvailable(effectiveModel, onChunk)
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

	/**
	 * Checks if a model is available locally on the Ollama instance.
	 */
	internal suspend fun isModelAvailable(model: String): Boolean {
		return try {
			val response = client.get("$baseUrl/api/tags")
			if (response.status != HttpStatusCode.OK) return false
			val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
			val models = json["models"]?.jsonArray ?: return false
			models.any { entry ->
				val name = entry.jsonObject["name"]?.jsonPrimitive?.content ?: ""
				// Match both "llama3.2" and "llama3.2:latest" style names
				name == model || name.startsWith("$model:")
			}
		} catch (e: Exception) {
			logger.warn("Failed to check model availability for '$model': ${e.message}")
			false
		}
	}

	/**
	 * Pulls a model from the Ollama registry, streaming progress updates.
	 * Returns true if the pull succeeded.
	 */
	internal suspend fun pullModel(model: String, onProgress: suspend (String) -> Unit): Boolean {
		logger.info("Pulling Ollama model '$model'...")
		onProgress("\n📦 Model '$model' not found locally. Pulling from Ollama registry...\n")

		try {
			val body = buildJsonObject {
				put("name", model)
				put("stream", true)
			}

			val response = client.post("$baseUrl/api/pull") {
				contentType(ContentType.Application.Json)
				setBody(body.toString())
			}

			if (response.status != HttpStatusCode.OK) {
				val errorBody = response.bodyAsText()
				logger.error("Ollama pull returned ${response.status}: $errorBody")
				onProgress("❌ Failed to pull model: ${response.status}\n")
				return false
			}

			val channel = response.bodyAsChannel()
			val buffer = StringBuilder()
			var lastPercentLogged = -1

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
						val status = json["status"]?.jsonPrimitive?.content ?: ""
						val total = json["total"]?.jsonPrimitive?.longOrNull
						val completed = json["completed"]?.jsonPrimitive?.longOrNull

						if (total != null && total > 0 && completed != null) {
							val percent = (completed * 100 / total).toInt()
							// Only report every 10% to avoid flooding
							val bucket = (percent / 10) * 10
							if (bucket > lastPercentLogged) {
								lastPercentLogged = bucket
								val totalMB = total / (1024 * 1024)
								val completedMB = completed / (1024 * 1024)
								onProgress("📥 $status: ${completedMB}MB / ${totalMB}MB ($percent%)\n")
							}
						} else if (status.isNotEmpty() && !status.contains("pulling")) {
							onProgress("📥 $status\n")
						}

						// Check for error in the response
						val error = json["error"]?.jsonPrimitive?.content
						if (error != null) {
							logger.error("Ollama pull error: $error")
							onProgress("❌ Pull error: $error\n")
							return false
						}
					} catch (e: Exception) {
						logger.debug("Failed to parse pull progress: $line", e)
					}
				}
			}

			logger.info("Successfully pulled model '$model'")
			onProgress("✅ Model '$model' pulled successfully!\n\n")
			return true
		} catch (e: Exception) {
			logger.error("Failed to pull model '$model': ${e.message}", e)
			onProgress("❌ Failed to pull model: ${e.message}\n")
			return false
		}
	}

	/**
	 * Ensures the requested model is available, pulling it if necessary.
	 */
	private suspend fun ensureModelAvailable(model: String, onProgress: suspend (String) -> Unit) {
		if (model in verifiedModels) return
		if (isModelAvailable(model)) {
			verifiedModels.add(model)
			return
		}
		logger.info("Model '$model' not available locally, attempting to pull...")
		val pulled = pullModel(model, onProgress)
		if (pulled) {
			verifiedModels.add(model)
		} else {
			throw RuntimeException("Model '$model' is not available and could not be pulled")
		}
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

fun createProvider(config: ProviderConfig): LlmProvider = when (config.type) {
	ProviderType.OLLAMA -> OllamaProvider(config)
}
