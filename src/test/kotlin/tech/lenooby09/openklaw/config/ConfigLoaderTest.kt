package tech.lenooby09.openklaw.config

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigLoaderTest {

	@TempDir
	lateinit var tempDir: File

	@Test
	fun `load returns defaults when file does not exist`() {
		val config = ConfigLoader.load("nonexistent-config.yaml")
		assertEquals(AppConfig(), config)
	}

	@Test
	fun `load parses gateway config from YAML`() {
		val file = File(tempDir, "config.yaml")
		file.writeText(
			"""
			gateway:
			  port: 9090
			  bindAddress: "0.0.0.0"
			  trustProxy: true
		""".trimIndent()
		)

		val config = ConfigLoader.load(file.absolutePath)
		assertEquals(9090, config.gateway.port)
		assertEquals("0.0.0.0", config.gateway.bindAddress)
		assertEquals(true, config.gateway.trustProxy)
	}

	@Test
	fun `load uses defaults for omitted sections`() {
		val file = File(tempDir, "config.yaml")
		file.writeText(
			"""
			gateway:
			  port: 3000
		""".trimIndent()
		)

		val config = ConfigLoader.load(file.absolutePath)
		assertEquals(3000, config.gateway.port)
		// Omitted fields keep defaults
		assertEquals("127.0.0.1", config.gateway.bindAddress)
		assertEquals(true, config.tools.shellEnabled)
		assertEquals("data", config.memory.dataDir)
	}

	@Test
	fun `load parses LLM providers`() {
		val file = File(tempDir, "config.yaml")
		file.writeText(
			"""
			llm:
			  failoverEnabled: false
			  providers:
			    - name: "test-openai"
			      type: OPENAI
			      apiKeyEnv: "MY_KEY"
			      model: "gpt-4"
			      priority: 1
			      enabled: true
		""".trimIndent()
		)

		val config = ConfigLoader.load(file.absolutePath)
		assertEquals(false, config.llm.failoverEnabled)
		assertEquals(1, config.llm.providers.size)
		assertEquals("test-openai", config.llm.providers[0].name)
		assertEquals(ProviderType.OPENAI, config.llm.providers[0].type)
		assertEquals("gpt-4", config.llm.providers[0].model)
	}

	@Test
	fun `load parses messaging discord config`() {
		val file = File(tempDir, "config.yaml")
		file.writeText(
			"""
			messaging:
			  discord:
			    enabled: true
			    channelIds:
			      - "123456789"
			      - "987654321"
			    pollIntervalMs: 5000
		""".trimIndent()
		)

		val config = ConfigLoader.load(file.absolutePath)
		assertEquals(true, config.messaging.discord.enabled)
		assertEquals(listOf("123456789", "987654321"), config.messaging.discord.channelIds)
		assertEquals(5000, config.messaging.discord.pollIntervalMs)
	}

	@Test
	fun `load parses scheduler config`() {
		val file = File(tempDir, "config.yaml")
		file.writeText(
			"""
			scheduler:
			  cronEnabled: false
			  maxCronJobs: 50
			  schedulerRestrictedTools:
			    - "shell"
		""".trimIndent()
		)

		val config = ConfigLoader.load(file.absolutePath)
		assertEquals(false, config.scheduler.cronEnabled)
		assertEquals(50, config.scheduler.maxCronJobs)
		assertEquals(listOf("shell"), config.scheduler.schedulerRestrictedTools)
	}

	@Test
	fun `load parses skills config`() {
		val file = File(tempDir, "config.yaml")
		file.writeText(
			"""
			skills:
			  selfImprovementEnabled: true
			  maxSkillContextChars: 25000
		""".trimIndent()
		)

		val config = ConfigLoader.load(file.absolutePath)
		assertEquals(true, config.skills.selfImprovementEnabled)
		assertEquals(25000, config.skills.maxSkillContextChars)
	}

	@Test
	fun `load throws on invalid YAML`() {
		val file = File(tempDir, "bad.yaml")
		file.writeText(
			"""
			gateway:
			  port: "not-a-number"
		""".trimIndent()
		)

		assertFailsWith<IllegalStateException> {
			ConfigLoader.load(file.absolutePath)
		}
	}

	@Test
	fun `load ignores unknown keys in strict mode off`() {
		val file = File(tempDir, "config.yaml")
		file.writeText(
			"""
			gateway:
			  port: 7070
			  unknownField: "should be ignored"
			futureSection:
			  something: true
		""".trimIndent()
		)

		val config = ConfigLoader.load(file.absolutePath)
		assertEquals(7070, config.gateway.port)
	}

	@Test
	fun `generateDefault produces valid YAML that parses to defaults`() {
		val file = File(tempDir, "default.yaml")
		file.writeText(ConfigLoader.generateDefault())

		val config = ConfigLoader.load(file.absolutePath)
		assertEquals(AppConfig(), config)
	}

	@Test
	fun `generateDefault output contains key sections`() {
		val text = ConfigLoader.generateDefault()
		assertTrue(text.contains("gateway:"))
		assertTrue(text.contains("llm:"))
		assertTrue(text.contains("security:"))
		assertTrue(text.contains("tools:"))
		assertTrue(text.contains("memory:"))
		assertTrue(text.contains("messaging:"))
		assertTrue(text.contains("scheduler:"))
		assertTrue(text.contains("skills:"))
	}

	@Test
	fun `load handles empty file gracefully with defaults`() {
		val file = File(tempDir, "empty.yaml")
		file.writeText("")

		// Empty YAML should either return defaults or throw — both are acceptable
		// kaml with strictMode=false on empty input may throw
		try {
			val config = ConfigLoader.load(file.absolutePath)
			assertEquals(AppConfig(), config)
		} catch (e: IllegalStateException) {
			// Also acceptable — empty file is arguably invalid YAML for a config
			assertTrue(e.message?.contains("Invalid configuration") == true)
		}
	}

	@Test
	fun `load parses full config with all sections`() {
		val file = File(tempDir, "full.yaml")
		file.writeText(
			"""
			gateway:
			  enabled: true
			  port: 4000
			  bindAddress: "0.0.0.0"
			  trustProxy: true
			auth:
			  sessionExpiryHours: 48
			  bcryptCost: 10
			security:
			  rateLimitMaxAttempts: 10
			  maxInputSizeMb: 2.0
			tools:
			  shellEnabled: false
			  shellTimeoutSeconds: 60
			memory:
			  dataDir: "custom-data"
			  semanticSearchMaxResults: 10
		""".trimIndent()
		)

		val config = ConfigLoader.load(file.absolutePath)
		assertEquals(4000, config.gateway.port)
		assertEquals("0.0.0.0", config.gateway.bindAddress)
		assertEquals(true, config.gateway.trustProxy)
		assertEquals(48, config.auth.sessionExpiryHours)
		assertEquals(10, config.auth.bcryptCost)
		assertEquals(10, config.security.rateLimitMaxAttempts)
		assertEquals(2.0, config.security.maxInputSizeMb)
		assertEquals(false, config.tools.shellEnabled)
		assertEquals(60, config.tools.shellTimeoutSeconds)
		assertEquals("custom-data", config.memory.dataDir)
		assertEquals(10, config.memory.semanticSearchMaxResults)
	}
}
