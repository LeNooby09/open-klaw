package tech.lenooby09.openklaw.tools

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tech.lenooby09.openklaw.config.ToolsConfig

class CanvasToolTest {
	private lateinit var tool: CanvasTool

	@BeforeEach
	fun setup() {
		tool = CanvasTool(ToolsConfig(canvasEnabled = true))
	}

	@Test
	fun `push canvas item`() = runBlocking {
		val result = tool.execute(mapOf("action" to "push", "type" to "html", "title" to "Test", "content" to "<h1>Hello</h1>"))
		assertTrue(result.success)
		assertTrue(result.metadata.containsKey("id"))
		assertEquals(1, tool.getItems().size)
	}

	@Test
	fun `push and list items`() = runBlocking {
		tool.execute(mapOf("action" to "push", "content" to "Item 1"))
		tool.execute(mapOf("action" to "push", "content" to "Item 2"))

		val result = tool.execute(mapOf("action" to "list"))
		assertTrue(result.success)
		assertEquals(2, tool.getItems().size)
	}

	@Test
	fun `update canvas item`() = runBlocking {
		val pushResult = tool.execute(mapOf("action" to "push", "content" to "Original"))
		val id = pushResult.metadata["id"]!!

		val updateResult = tool.execute(mapOf("action" to "update", "id" to id, "content" to "Updated"))
		assertTrue(updateResult.success)
		assertEquals("Updated", tool.getItem(id)?.content)
	}

	@Test
	fun `remove canvas item`() = runBlocking {
		val pushResult = tool.execute(mapOf("action" to "push", "content" to "To Remove"))
		val id = pushResult.metadata["id"]!!

		val removeResult = tool.execute(mapOf("action" to "remove", "id" to id))
		assertTrue(removeResult.success)
		assertNull(tool.getItem(id))
	}

	@Test
	fun `clear canvas`() = runBlocking {
		tool.execute(mapOf("action" to "push", "content" to "A"))
		tool.execute(mapOf("action" to "push", "content" to "B"))

		val result = tool.execute(mapOf("action" to "clear"))
		assertTrue(result.success)
		assertTrue(tool.getItems().isEmpty())
	}

	@Test
	fun `push without content fails`() = runBlocking {
		val result = tool.execute(mapOf("action" to "push"))
		assertFalse(result.success)
		assertTrue(result.error!!.contains("Missing"))
	}

	@Test
	fun `update non-existent item fails`() = runBlocking {
		val result = tool.execute(mapOf("action" to "update", "id" to "nonexistent", "content" to "X"))
		assertFalse(result.success)
		assertTrue(result.error!!.contains("not found"))
	}

	@Test
	fun `remove non-existent item fails`() = runBlocking {
		val result = tool.execute(mapOf("action" to "remove", "id" to "nonexistent"))
		assertFalse(result.success)
	}

	@Test
	fun `list empty canvas`() = runBlocking {
		val result = tool.execute(mapOf("action" to "list"))
		assertTrue(result.success)
		assertTrue(result.output.contains("empty"))
	}

	@Test
	fun `tool name is canvas`() {
		assertEquals("canvas", tool.name)
	}

	@Test
	fun `disabled tool`() {
		val disabledTool = CanvasTool(ToolsConfig(canvasEnabled = false))
		assertFalse(disabledTool.enabled)
	}
}
