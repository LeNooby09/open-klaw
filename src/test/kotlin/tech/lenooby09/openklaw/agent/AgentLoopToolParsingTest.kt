package tech.lenooby09.openklaw.agent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tech.lenooby09.openklaw.config.LlmConfig
import tech.lenooby09.openklaw.llm.LlmOrchestrator

class AgentLoopToolParsingTest {
	private val orchestrator = LlmOrchestrator(LlmConfig())
	private val agentLoop = AgentLoop(orchestrator)

	@Test
	fun `parse valid tool call`() {
		val content = """
			Let me run that command for you.
			```tool
			{"tool": "shell", "arguments": {"command": "ls -la"}}
			```
			I'll check the output.
		""".trimIndent()

		val request = agentLoop.parseToolCall(content)
		assertNotNull(request)
		assertEquals("shell", request!!.toolName)
		assertEquals("ls -la", request.arguments["command"])
	}

	@Test
	fun `parse tool call with multiple arguments`() {
		val content = """
			```tool
			{"tool": "file", "arguments": {"operation": "write", "path": "test.txt", "content": "hello world"}}
			```
		""".trimIndent()

		val request = agentLoop.parseToolCall(content)
		assertNotNull(request)
		assertEquals("file", request!!.toolName)
		assertEquals("write", request.arguments["operation"])
		assertEquals("test.txt", request.arguments["path"])
		assertEquals("hello world", request.arguments["content"])
	}

	@Test
	fun `parse returns null for no tool call`() {
		val content = "Just a regular response with no tool calls."
		val request = agentLoop.parseToolCall(content)
		assertNull(request)
	}

	@Test
	fun `parse returns null for malformed json`() {
		val content = """
			```tool
			{not valid json}
			```
		""".trimIndent()

		val request = agentLoop.parseToolCall(content)
		assertNull(request)
	}

	@Test
	fun `parse returns null for missing tool name`() {
		val content = """
			```tool
			{"arguments": {"command": "ls"}}
			```
		""".trimIndent()

		val request = agentLoop.parseToolCall(content)
		assertNull(request)
	}

	@Test
	fun `parse tool call with no arguments`() {
		val content = """
			```tool
			{"tool": "canvas", "arguments": {}}
			```
		""".trimIndent()

		val request = agentLoop.parseToolCall(content)
		assertNotNull(request)
		assertEquals("canvas", request!!.toolName)
		assertTrue(request.arguments.isEmpty())
	}
}
