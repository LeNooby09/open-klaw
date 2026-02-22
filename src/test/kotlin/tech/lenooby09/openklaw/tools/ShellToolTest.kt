package tech.lenooby09.openklaw.tools

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tech.lenooby09.openklaw.config.ToolsConfig

class ShellToolTest {
	private val config = ToolsConfig(shellEnabled = true)
	private val tool = ShellTool(config)

	@Test
	fun `execute simple command`() = runBlocking {
		val result = tool.execute(mapOf("command" to "echo hello"))
		assertTrue(result.success)
		assertTrue(result.output.contains("hello"))
	}

	@Test
	fun `execute command with exit code`() = runBlocking {
		val result = tool.execute(mapOf("command" to "false"))
		assertFalse(result.success)
		assertTrue(result.output.contains("exit code: 1"))
	}

	@Test
	fun `capture stderr`() = runBlocking {
		val result = tool.execute(mapOf("command" to "echo error >&2"))
		assertTrue(result.output.contains("[stderr]"))
		assertTrue(result.output.contains("error"))
	}

	@Test
	fun `missing command argument`() = runBlocking {
		val result = tool.execute(emptyMap())
		assertFalse(result.success)
		assertTrue(result.error!!.contains("Missing"))
	}

	@Test
	fun `invalid working directory`() = runBlocking {
		val result = tool.execute(mapOf("command" to "echo hi", "workingDir" to "/nonexistent_dir_12345"))
		assertFalse(result.success)
		assertTrue(result.error!!.contains("Working directory"))
	}

	@Test
	fun `command with custom timeout`() = runBlocking {
		val result = tool.execute(mapOf("command" to "echo fast", "timeout" to "5"))
		assertTrue(result.success)
		assertTrue(result.output.contains("fast"))
	}

	@Test
	fun `tool is disabled`() {
		val disabledConfig = ToolsConfig(shellEnabled = false)
		val disabledTool = ShellTool(disabledConfig)
		assertFalse(disabledTool.enabled)
	}

	@Test
	fun `tool name is shell`() {
		assertEquals("shell", tool.name)
	}
}
