package tech.lenooby09.openklaw.tools

import kotlinx.serialization.Serializable

/**
 * Represents a parameter that a tool accepts.
 */
@Serializable
data class ToolParameter(
	val name: String,
	val description: String,
	val type: String = "string",
	val required: Boolean = true,
	val defaultValue: String? = null
)

/**
 * The result of executing a tool.
 */
@Serializable
data class ToolResult(
	val toolName: String,
	val success: Boolean,
	val output: String,
	val error: String? = null,
	val metadata: Map<String, String> = emptyMap(),
	val executionTimeMs: Long = 0
)

/**
 * A request to execute a tool with named arguments.
 */
@Serializable
data class ToolExecutionRequest(
	val toolName: String,
	val arguments: Map<String, String> = emptyMap()
)

/**
 * Pluggable tool interface. All tools in Open-Klaw implement this contract
 * so they can be discovered, described, and executed uniformly by the agent.
 */
interface Tool {
	/** Unique identifier for this tool (e.g. "shell", "file_read"). */
	val name: String

	/** Human-readable description shown to the LLM so it knows when to use this tool. */
	val description: String

	/** The parameters this tool accepts. */
	val parameters: List<ToolParameter>

	/** Whether the tool is currently enabled and operational. */
	val enabled: Boolean

	/**
	 * Whether this tool is idempotent (safe to retry on failure).
	 * Non-idempotent tools (e.g. shell, filesystem writes) will not be retried
	 * because re-execution after partial completion could cause unintended side effects.
	 * Defaults to false (conservative — no retry unless explicitly marked safe).
	 */
	val idempotent: Boolean get() = false

	/** Execute the tool with the given arguments and return a result. */
	suspend fun execute(arguments: Map<String, String>): ToolResult
}
