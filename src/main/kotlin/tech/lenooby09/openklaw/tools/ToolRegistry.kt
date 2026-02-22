package tech.lenooby09.openklaw.tools

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.agent.RetryPolicy
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ToolInfo(
	val name: String,
	val description: String,
	val parameters: List<ToolParameter>,
	val enabled: Boolean
)

/**
 * Central registry for all tools available to the agent.
 * Tools can be registered and unregistered at runtime.
 */
class ToolRegistry(
	private val toolRetryPolicy: RetryPolicy = RetryPolicy.TOOL_DEFAULT
) {
	private val logger = LoggerFactory.getLogger(ToolRegistry::class.java)
	private val tools = ConcurrentHashMap<String, Tool>()

	fun register(tool: Tool) {
		tools[tool.name] = tool
		logger.info("Registered tool: ${tool.name} — ${tool.description}")
	}

	fun unregister(toolName: String): Boolean {
		val removed = tools.remove(toolName) != null
		if (removed) {
			logger.info("Unregistered tool: $toolName")
		}
		return removed
	}

	fun get(toolName: String): Tool? = tools[toolName]

	fun listTools(): List<ToolInfo> = tools.values.map {
		ToolInfo(it.name, it.description, it.parameters, it.enabled)
	}.sortedBy { it.name }

	fun listEnabled(): List<ToolInfo> = listTools().filter { it.enabled }

	fun isRegistered(toolName: String): Boolean = tools.containsKey(toolName)

	fun getToolCount(): Int = tools.size

	suspend fun execute(request: ToolExecutionRequest): ToolResult {
		val tool = tools[request.toolName]
			?: return ToolResult(
				toolName = request.toolName,
				success = false,
				output = "",
				error = "Tool '${request.toolName}' not found. Available tools: ${tools.keys.sorted().joinToString()}"
			)

		if (!tool.enabled) {
			return ToolResult(
				toolName = request.toolName,
				success = false,
				output = "",
				error = "Tool '${request.toolName}' is currently disabled."
			)
		}

		// Validate required parameters
		val missing = tool.parameters
			.filter { it.required && !request.arguments.containsKey(it.name) && it.defaultValue == null }
			.map { it.name }
		if (missing.isNotEmpty()) {
			return ToolResult(
				toolName = request.toolName,
				success = false,
				output = "",
				error = "Missing required parameters: ${missing.joinToString()}"
			)
		}

		val startTime = System.currentTimeMillis()
		// Only retry idempotent tools — non-idempotent tools (shell, filesystem, etc.)
		// could cause unintended side effects if re-executed after partial completion
		val effectivePolicy = if (tool.idempotent) toolRetryPolicy else RetryPolicy.NONE
		return try {
			val result = effectivePolicy.execute("Tool '${request.toolName}'") { attempt ->
				if (attempt > 1) logger.debug("Retrying tool '${request.toolName}' (attempt $attempt)")
				tool.execute(request.arguments)
			}
			result.copy(executionTimeMs = System.currentTimeMillis() - startTime)
		} catch (e: Exception) {
			logger.error("Tool '${request.toolName}' execution failed: ${e.message}", e)
			ToolResult(
				toolName = request.toolName,
				success = false,
				output = "",
				error = "Tool execution failed: ${e.message}",
				executionTimeMs = System.currentTimeMillis() - startTime
			)
		}
	}

	/**
	 * Build a system prompt section describing all enabled tools to the LLM.
	 * @param allowedTools If non-null, only include tools whose names are in this set.
	 */
	fun buildToolDescriptions(allowedTools: Set<String>? = null): String {
		val enabledTools = tools.values.filter { it.enabled && (allowedTools == null || it.name in allowedTools) }
		if (enabledTools.isEmpty()) return ""

		return buildString {
			appendLine("\n\nYou have access to the following tools. To use a tool, respond with a JSON block in this format:")
			appendLine("```tool")
			appendLine("""{"tool": "tool_name", "arguments": {"param1": "value1", "param2": "value2"}}""")
			appendLine("```")
			appendLine()
			appendLine("Available tools:")
			for (tool in enabledTools.sortedBy { it.name }) {
				appendLine()
				appendLine("### ${tool.name}")
				appendLine(tool.description)
				if (tool.parameters.isNotEmpty()) {
					appendLine("Parameters:")
					for (param in tool.parameters) {
						val reqTag = if (param.required) "required" else "optional"
						val defaultTag = if (param.defaultValue != null) ", default: ${param.defaultValue}" else ""
						appendLine("  - ${param.name} (${param.type}, $reqTag$defaultTag): ${param.description}")
					}
				}
			}
			appendLine()
			appendLine("When you want to use a tool, include exactly one ```tool block in your response. After the tool executes, you will receive the result and can continue your response.")
		}
	}
}
