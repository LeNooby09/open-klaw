package tech.lenooby09.openklaw.llm

import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.LlmConfig

class LlmOrchestrator(private val config: LlmConfig) {
	private val logger = LoggerFactory.getLogger(LlmOrchestrator::class.java)
	private val providers: MutableList<LlmProvider> = mutableListOf()

	fun initialize() {
		providers.clear()
		config.providers
			.filter { it.enabled }
			.sortedBy { it.priority }
			.forEach { providerConfig ->
				try {
					val provider = createProvider(providerConfig)
					providers.add(provider)
					logger.info("Registered LLM provider: ${provider.name} (model: ${providerConfig.model})")
				} catch (e: Exception) {
					logger.error("Failed to create provider ${providerConfig.name}: ${e.message}")
				}
			}

		if (providers.isEmpty()) {
			logger.warn("No LLM providers configured — agent will respond with placeholder messages")
		}
	}

	suspend fun complete(messages: List<LlmMessage>): LlmResponse {
		if (providers.isEmpty()) {
			return LlmResponse(
				content = "No LLM providers are configured. Add provider configuration to enable AI responses.",
				model = "none",
				provider = "system"
			)
		}

		if (!config.failoverEnabled) {
			return providers.first().complete(messages, "")
		}

		for (provider in providers) {
			if (!provider.isAvailable) continue
			try {
				return provider.complete(messages, "")
			} catch (e: Exception) {
				logger.warn("Provider ${provider.name} failed, trying next: ${e.message}")
			}
		}

		return LlmResponse(
			content = "All LLM providers are currently unavailable. Please check your configuration.",
			model = "none",
			provider = "system"
		)
	}

	suspend fun completeStream(messages: List<LlmMessage>, onChunk: suspend (String) -> Unit): LlmResponse {
		if (providers.isEmpty()) {
			val msg = "No LLM providers are configured. Add provider configuration to enable AI responses."
			onChunk(msg)
			return LlmResponse(content = msg, model = "none", provider = "system")
		}

		if (!config.failoverEnabled) {
			return providers.first().completeStream(messages, "", onChunk)
		}

		for (provider in providers) {
			if (!provider.isAvailable) continue
			try {
				return provider.completeStream(messages, "", onChunk)
			} catch (e: Exception) {
				logger.warn("Provider ${provider.name} failed streaming, trying next: ${e.message}")
			}
		}

		val msg = "All LLM providers are currently unavailable. Please check your configuration."
		onChunk(msg)
		return LlmResponse(content = msg, model = "none", provider = "system")
	}

	fun getProviderCount(): Int = providers.size

	fun getProviders(): List<LlmProvider> = providers.toList()

	suspend fun healthCheck(): Map<String, Boolean> {
		return providers.associate { it.name to it.checkHealth() }
	}
}
