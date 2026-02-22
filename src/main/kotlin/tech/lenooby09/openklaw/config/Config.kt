package tech.lenooby09.openklaw.config

import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
	val gateway: GatewayConfig = GatewayConfig(),
	val llm: LlmConfig = LlmConfig(),
	val auth: AuthConfig = AuthConfig(),
	val security: SecurityConfig = SecurityConfig(),
	val tools: ToolsConfig = ToolsConfig(),
	val memory: MemoryConfig = MemoryConfig(),
	val messaging: MessagingConfig = MessagingConfig()
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
	val conversationIdleTimeoutMinutes: Int = 1440
) {
	fun computeMaxInputSizeBytes(): Long = (maxInputSizeMb * 1024 * 1024).toLong()
}

@Serializable
data class ToolsConfig(
	val shellEnabled: Boolean = true,
	val fileSystemEnabled: Boolean = true,
	val browserEnabled: Boolean = true,
	val canvasEnabled: Boolean = true,
	val shellTimeoutSeconds: Long = 30,
	val shellMaxOutputLength: Int = 50_000,
	val fileSystemBaseDir: String = ".",
	val fileSystemMaxFileSizeBytes: Long = 10 * 1024 * 1024,
	val browserHeadless: Boolean = true,
	val browserTimeoutSeconds: Long = 30
)

@Serializable
data class MemoryConfig(
	val enabled: Boolean = true,
	val dataDir: String = "data",
	val conversationLoggingEnabled: Boolean = true,
	val soulFile: String = "SOUL.md",
	val memoryFile: String = "MEMORY.md",
	val userFilePattern: String = "USER_{username}.md",
	val semanticSearchEnabled: Boolean = true,
	val semanticSearchMaxResults: Int = 5,
	val semanticSearchMinScore: Double = 0.1,
	val semanticSearchMaxDocuments: Int = 10_000,
	val memoryDistillationEnabled: Boolean = true,
	val memoryDistillationThresholdMessages: Int = 20,
	val maxMemoryFileSize: Long = 512 * 1024,
	val defaultUserStorageBudget: Long = 256 * 1024
)

// --- Phase 4: Messaging & Transport Integrations ---

@Serializable
data class MessagingConfig(
	val discord: DiscordConfig = DiscordConfig(),
	val telegram: TelegramConfig = TelegramConfig(),
	val whatsapp: WhatsAppConfig = WhatsAppConfig(),
	val slack: SlackConfig = SlackConfig(),
	val email: EmailConfig = EmailConfig(),
	val webChatEnabled: Boolean = true,
	val maxChannelMessageLength: Int = 10_000,
	val channelSessionMapMaxSize: Int = 50_000,
	val channelDedupMaxSize: Int = 10_000
)

@Serializable
data class DiscordConfig(
	val enabled: Boolean = false,
	val botTokenEnv: String = "DISCORD_BOT_TOKEN",
	val channelIds: List<String> = emptyList(),
	val pollIntervalMs: Long = 2000,
	val respondToAll: Boolean = false
) {
	val botToken: String get() = if (botTokenEnv.isNotEmpty()) System.getenv(botTokenEnv) ?: "" else ""
}

@Serializable
data class TelegramConfig(
	val enabled: Boolean = false,
	val botTokenEnv: String = "TELEGRAM_BOT_TOKEN",
	val longPollTimeoutSeconds: Long = 30,
	val respondToAllGroupMessages: Boolean = false
) {
	val botToken: String get() = if (botTokenEnv.isNotEmpty()) System.getenv(botTokenEnv) ?: "" else ""
}

@Serializable
data class WhatsAppConfig(
	val enabled: Boolean = false,
	val accessTokenEnv: String = "WHATSAPP_ACCESS_TOKEN",
	val appSecretEnv: String = "WHATSAPP_APP_SECRET",
	val phoneNumberId: String = "",
	val webhookVerifyToken: String = "",
) {
	val accessToken: String get() = if (accessTokenEnv.isNotEmpty()) System.getenv(accessTokenEnv) ?: "" else ""
	val appSecret: String get() = if (appSecretEnv.isNotEmpty()) System.getenv(appSecretEnv) ?: "" else ""
}

@Serializable
data class SlackConfig(
	val enabled: Boolean = false,
	val botTokenEnv: String = "SLACK_BOT_TOKEN",
	val signingSecretEnv: String = "SLACK_SIGNING_SECRET",
	val channelIds: List<String> = emptyList(),
	val respondToOtherBots: Boolean = false
) {
	val botToken: String get() = if (botTokenEnv.isNotEmpty()) System.getenv(botTokenEnv) ?: "" else ""
	val signingSecret: String get() = if (signingSecretEnv.isNotEmpty()) System.getenv(signingSecretEnv) ?: "" else ""
}

@Serializable
data class EmailConfig(
	val enabled: Boolean = false,
	val username: String = "",
	val passwordEnv: String = "EMAIL_PASSWORD",
	val imapHost: String = "imap.gmail.com",
	val imapPort: Int = 993,
	val imapSsl: Boolean = true,
	val imapStartTls: Boolean = false,
	val smtpHost: String = "smtp.gmail.com",
	val smtpPort: Int = 587,
	val smtpSsl: Boolean = false,
	val smtpStartTls: Boolean = true,
	val fromName: String = "Open-Klaw",
	val inboxFolder: String = "INBOX",
	val pollIntervalMs: Long = 30_000,
	val allowedSenders: List<String> = emptyList(),
	val maxEmailBodyLength: Int = 10_000
) {
	val password: String get() = if (passwordEnv.isNotEmpty()) System.getenv(passwordEnv) ?: "" else ""
}
