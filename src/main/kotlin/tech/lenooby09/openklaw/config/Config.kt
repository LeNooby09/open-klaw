package tech.lenooby09.openklaw.config

import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
	val gateway: GatewayConfig = GatewayConfig(),
	val llm: LlmConfig = LlmConfig(),
	val auth: AuthConfig = AuthConfig(),
	val security: SecurityConfig = SecurityConfig()
)

@Serializable
data class GatewayConfig(
	val enabled: Boolean = true,
	val port: Int = 8080,
	val bindAddress: String = "127.0.0.1"
)

@Serializable
data class LlmConfig(
	val providers: List<ProviderConfig> = emptyList(),
	val failoverEnabled: Boolean = true
)

@Serializable
data class ProviderConfig(
	val name: String,
	val type: ProviderType,
	val apiKeyEnv: String = "",
	val baseUrl: String = "",
	val model: String = "",
	val priority: Int = 0,
	val enabled: Boolean = true
) {
	fun resolveApiKey(): String = if (apiKeyEnv.isNotEmpty()) System.getenv(apiKeyEnv) ?: "" else ""
}

@Serializable
enum class ProviderType {
	OPENAI, ANTHROPIC, OLLAMA, OPENROUTER
}

@Serializable
data class AuthConfig(
	val sessionExpiryHours: Int = 24,
	val bcryptCost: Int = 12
)

@Serializable
data class SecurityConfig(
	val rateLimitBaseDelayMs: Long = 1000,
	val rateLimitMaxAttempts: Int = 5,
	val rateLimitWindowMs: Long = 60_000,
	val sessionCleanupIntervalMinutes: Int = 15,
	val maxInputSizeMb: Double = 1.0,
	val maxInputSizeBytes: Long = (1.0 * 1024 * 1024).toLong()
) {
	fun computeMaxInputSizeBytes(): Long = (maxInputSizeMb * 1024 * 1024).toLong()
}
