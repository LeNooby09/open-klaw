package tech.lenooby09.openklaw.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import org.slf4j.LoggerFactory
import java.io.File

object ConfigLoader {
	private val logger = LoggerFactory.getLogger(ConfigLoader::class.java)
	private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

	fun load(path: String = "config.yaml"): AppConfig {
		val file = File(path)
		if (!file.exists() || file.readText().isBlank()) {
			logger.info("No config file found at '${file.absolutePath}' — generating default config")
			file.writeText(generateDefault())
			logger.info("Default configuration written to '${file.absolutePath}'")
			return AppConfig()
		}
		return try {
			val text = file.readText()
			val config = yaml.decodeFromString(AppConfig.serializer(), text)
			logger.info("Configuration loaded from '${file.absolutePath}'")
			config
		} catch (e: Exception) {
			logger.error("Failed to parse config file '${file.absolutePath}': ${e.message}")
			throw IllegalStateException("Invalid configuration file '${file.absolutePath}': ${e.message}", e)
		}
	}

	fun generateDefault(): String {
		return """
			|# Open-Klaw Configuration
			|# Copy this file to config.yaml and adjust as needed.
			|# All values shown are the defaults — uncomment and modify to override.
			|
			|gateway:
			|  enabled: true
			|  port: 8080
			|  bindAddress: "127.0.0.1"
			|  trustProxy: false
			|
			|llm:
			|  failoverEnabled: true
			|  providers: []
			|  # Example provider configuration:
			|  # providers:
			|  #   - name: "openai"
			|  #     type: OPENAI
			|  #     apiKeyEnv: "OPENAI_API_KEY"
			|  #     model: "gpt-4"
			|  #     priority: 1
			|  #     enabled: true
			|  #   - name: "ollama-local"
			|  #     type: OLLAMA
			|  #     baseUrl: "http://localhost:11434"
			|  #     model: "llama3"
			|  #     priority: 2
			|  #     enabled: true
			|
			|auth:
			|  sessionExpiryHours: 24
			|  bcryptCost: 12
			|
			|security:
			|  rateLimitBaseDelayMs: 1000
			|  rateLimitMaxAttempts: 5
			|  rateLimitWindowMs: 60000
			|  sessionCleanupIntervalMinutes: 15
			|  maxInputSizeMb: 1.0
			|  conversationIdleTimeoutMinutes: 1440
			|
			|tools:
			|  shellEnabled: true
			|  fileSystemEnabled: true
			|  browserEnabled: true
			|  canvasEnabled: true
			|  shellTimeoutSeconds: 30
			|  shellMaxOutputLength: 50000
			|  fileSystemBaseDir: "."
			|  fileSystemMaxFileSizeBytes: 10485760
			|  browserHeadless: true
			|  browserTimeoutSeconds: 30
			|
			|memory:
			|  enabled: true
			|  dataDir: "data"
			|  conversationLoggingEnabled: true
			|  soulFile: "SOUL.md"
			|  memoryFile: "MEMORY.md"
			|  userFilePattern: "USER_{username}.md"
			|  semanticSearchEnabled: true
			|  semanticSearchMaxResults: 5
			|  semanticSearchMinScore: 0.1
			|  semanticSearchMaxDocuments: 10000
			|  memoryDistillationEnabled: true
			|  memoryDistillationThresholdMessages: 20
			|  maxMemoryFileSize: 524288
			|  defaultUserStorageBudget: 262144
			|
			|messaging:
			|  webChatEnabled: true
			|  maxChannelMessageLength: 10000
			|  channelSessionMapMaxSize: 50000
			|  channelDedupMaxSize: 10000
			|  discord:
			|    enabled: false
			|    botTokenEnv: "DISCORD_BOT_TOKEN"
			|    channelIds: []
			|    pollIntervalMs: 2000
			|    respondToAll: false
			|  telegram:
			|    enabled: false
			|    botTokenEnv: "TELEGRAM_BOT_TOKEN"
			|    longPollTimeoutSeconds: 30
			|    respondToAllGroupMessages: false
			|  whatsapp:
			|    enabled: false
			|    accessTokenEnv: "WHATSAPP_ACCESS_TOKEN"
			|    appSecretEnv: "WHATSAPP_APP_SECRET"
			|    phoneNumberId: ""
			|    webhookVerifyToken: ""
			|  slack:
			|    enabled: false
			|    botTokenEnv: "SLACK_BOT_TOKEN"
			|    signingSecretEnv: "SLACK_SIGNING_SECRET"
			|    channelIds: []
			|    respondToOtherBots: false
			|  email:
			|    enabled: false
			|    username: ""
			|    passwordEnv: "EMAIL_PASSWORD"
			|    imapHost: "imap.gmail.com"
			|    imapPort: 993
			|    imapSsl: true
			|    imapStartTls: false
			|    smtpHost: "smtp.gmail.com"
			|    smtpPort: 587
			|    smtpSsl: false
			|    smtpStartTls: true
			|    fromName: "Open-Klaw"
			|    inboxFolder: "INBOX"
			|    pollIntervalMs: 30000
			|    allowedSenders: []
			|    maxEmailBodyLength: 10000
			|
			|scheduler:
			|  heartbeatEnabled: true
			|  heartbeatFile: "HEARTBEAT.md"
			|  heartbeatCheckIntervalMinutes: 1
			|  cronEnabled: true
			|  maxCronJobs: 100
			|  webhookTriggersEnabled: true
			|  maxWebhookTriggers: 50
			|  webhookSecretEnv: "OPENKLAW_WEBHOOK_SECRET"
			|  webhookRateLimitMaxPerMinute: 30
			|  gitMonitorEnabled: false
			|  gitPollIntervalMinutes: 5
			|  gitCommandTimeoutSeconds: 30
			|  gitAllowedBaseDirs:
			|    - "/workspace"
			|  gitMaxFileReadBytes: 1048576
			|  gitRepositories: []
			|  notificationsEnabled: true
			|  defaultNotificationChannel: "WEBCHAT"
			|  maxConcurrentSchedulerTasks: 4
			|  schedulerRestrictedTools:
			|    - "shell"
			|    - "filesystem"
			|  schedulerSafeTools:
			|    - "browser"
			|    - "canvas"
			|
			|skills:
			|  enabled: true
			|  dataDir: "data"
			|  workspaceSkillsDir: "skills"
			|  maxSkills: 200
			|  autoApproveWorkspaceSkills: false
			|  registryUrl: "https://registry.openklaw.dev/api/v1"
			|  registryAllowedHosts:
			|    - "registry.openklaw.dev"
			|  selfImprovementEnabled: false
			|  maxSkillContextChars: 50000
		""".trimMargin()
	}
}
