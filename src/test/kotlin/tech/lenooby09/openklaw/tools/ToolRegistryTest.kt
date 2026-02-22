package tech.lenooby09.openklaw.tools

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ToolRegistryTest {
	private lateinit var registry: ToolRegistry

	@BeforeEach
	fun setup() {
		registry = ToolRegistry()
	}

	@Test
	fun `register and retrieve tool`() {
		val tool = DummyTool("test_tool", "A test tool")
		registry.register(tool)

		assertTrue(registry.isRegistered("test_tool"))
		assertEquals(1, registry.getToolCount())
		assertEquals("test_tool", registry.get("test_tool")?.name)
	}

	@Test
	fun `unregister tool`() {
		registry.register(DummyTool("test_tool", "A test tool"))
		assertTrue(registry.unregister("test_tool"))
		assertFalse(registry.isRegistered("test_tool"))
		assertEquals(0, registry.getToolCount())
	}

	@Test
	fun `unregister non-existent tool returns false`() {
		assertFalse(registry.unregister("non_existent"))
	}

	@Test
	fun `list tools returns sorted list`() {
		registry.register(DummyTool("zzz", "Z tool"))
		registry.register(DummyTool("aaa", "A tool"))

		val list = registry.listTools()
		assertEquals(2, list.size)
		assertEquals("aaa", list[0].name)
		assertEquals("zzz", list[1].name)
	}

	@Test
	fun `list enabled filters disabled tools`() {
		registry.register(DummyTool("enabled_tool", "Enabled", enabled = true))
		registry.register(DummyTool("disabled_tool", "Disabled", enabled = false))

		val enabled = registry.listEnabled()
		assertEquals(1, enabled.size)
		assertEquals("enabled_tool", enabled[0].name)
	}

	@Test
	fun `execute tool successfully`() = runBlocking {
		registry.register(DummyTool("echo", "Echo tool"))
		val result = registry.execute(ToolExecutionRequest("echo", mapOf("input" to "hello")))

		assertTrue(result.success)
		assertEquals("echo", result.toolName)
		assertTrue(result.executionTimeMs >= 0)
	}

	@Test
	fun `execute non-existent tool returns error`() = runBlocking {
		val result = registry.execute(ToolExecutionRequest("non_existent"))

		assertFalse(result.success)
		assertTrue(result.error!!.contains("not found"))
	}

	@Test
	fun `execute disabled tool returns error`() = runBlocking {
		registry.register(DummyTool("disabled", "Disabled tool", enabled = false))
		val result = registry.execute(ToolExecutionRequest("disabled"))

		assertFalse(result.success)
		assertTrue(result.error!!.contains("disabled"))
	}

	@Test
	fun `execute with missing required params returns error`() = runBlocking {
		registry.register(DummyTool("strict", "Strict tool", params = listOf(
			ToolParameter("required_param", "A required param", required = true)
		)))
		val result = registry.execute(ToolExecutionRequest("strict"))

		assertFalse(result.success)
		assertTrue(result.error!!.contains("Missing required parameters"))
	}

	@Test
	fun `build tool descriptions includes enabled tools`() {
		registry.register(DummyTool("my_tool", "Does something useful"))
		val desc = registry.buildToolDescriptions()

		assertTrue(desc.contains("my_tool"))
		assertTrue(desc.contains("Does something useful"))
		assertTrue(desc.contains("```tool"))
	}

	@Test
	fun `build tool descriptions empty when no tools`() {
		val desc = registry.buildToolDescriptions()
		assertEquals("", desc)
	}
}

private class DummyTool(
	override val name: String,
	override val description: String,
	override val enabled: Boolean = true,
	val params: List<ToolParameter> = emptyList()
) : Tool {
	override val parameters: List<ToolParameter> get() = params

	override suspend fun execute(arguments: Map<String, String>): ToolResult {
		return ToolResult(name, true, "executed: ${arguments}")
	}
}
