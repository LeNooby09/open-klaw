package tech.lenooby09.openklaw.config

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigHolderTest {

	@TempDir
	lateinit var tempDir: File

	@Test
	fun `current returns initial config`() {
		val initial = AppConfig(gateway = GatewayConfig(port = 9999))
		val holder = ConfigHolder(initial, File(tempDir, "config.yaml").absolutePath)
		assertEquals(9999, holder.current.gateway.port)
	}

	@Test
	fun `update persists config to disk and updates current`() {
		val configFile = File(tempDir, "config.yaml")
		val holder = ConfigHolder(AppConfig(), configFile.absolutePath)

		val updated = AppConfig(tools = ToolsConfig(maxToolCalls = 10))
		holder.update(updated)

		assertEquals(10, holder.current.tools.maxToolCalls)
		assertTrue(configFile.exists())
		assertTrue(configFile.readText().contains("10"))
	}

	@Test
	fun `update notifies listeners`() {
		val configFile = File(tempDir, "config.yaml")
		val holder = ConfigHolder(AppConfig(), configFile.absolutePath)

		var notifiedConfig: AppConfig? = null
		holder.onChange { notifiedConfig = it }

		val updated = AppConfig(gateway = GatewayConfig(port = 4444))
		holder.update(updated)

		assertEquals(4444, notifiedConfig?.gateway?.port)
	}

	@Test
	fun `reload reads config from disk`() {
		val configFile = File(tempDir, "config.yaml")
		configFile.writeText(
			"""
			gateway:
			  port: 7777
		""".trimIndent()
		)

		val holder = ConfigHolder(AppConfig(), configFile.absolutePath)
		assertEquals(8080, holder.current.gateway.port) // default

		holder.reload()
		assertEquals(7777, holder.current.gateway.port)
	}

	@Test
	fun `reload notifies listeners`() {
		val configFile = File(tempDir, "config.yaml")
		configFile.writeText(
			"""
			tools:
			  maxToolCalls: 15
		""".trimIndent()
		)

		val holder = ConfigHolder(AppConfig(), configFile.absolutePath)
		var notifiedMaxToolCalls = 0
		holder.onChange { notifiedMaxToolCalls = it.tools.maxToolCalls }

		holder.reload()
		assertEquals(15, notifiedMaxToolCalls)
	}

	@Test
	fun `reload keeps current config when file does not exist`() {
		val holder = ConfigHolder(
			AppConfig(tools = ToolsConfig(maxToolCalls = 42)),
			File(tempDir, "missing.yaml").absolutePath
		)
		holder.reload()
		assertEquals(42, holder.current.tools.maxToolCalls)
	}

	@Test
	fun `multiple listeners are all notified`() {
		val configFile = File(tempDir, "config.yaml")
		val holder = ConfigHolder(AppConfig(), configFile.absolutePath)

		var count = 0
		holder.onChange { count++ }
		holder.onChange { count++ }

		holder.update(AppConfig(gateway = GatewayConfig(port = 1111)))
		assertEquals(2, count)
	}

	@Test
	fun `failing listener does not prevent other listeners`() {
		val configFile = File(tempDir, "config.yaml")
		val holder = ConfigHolder(AppConfig(), configFile.absolutePath)

		var secondCalled = false
		holder.onChange { throw RuntimeException("boom") }
		holder.onChange { secondCalled = true }

		holder.update(AppConfig())
		assertTrue(secondCalled)
	}
}
