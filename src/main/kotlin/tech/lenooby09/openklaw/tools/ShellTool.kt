package tech.lenooby09.openklaw.tools

import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.ToolsConfig
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Executes shell commands in a sandboxed environment.
 * Respects configurable timeouts, working directory, and blocked command patterns.
 */
class ShellTool(private val config: ToolsConfig) : Tool {
	private val logger = LoggerFactory.getLogger(ShellTool::class.java)

	override val name = "shell"
	override val description = "Run a shell command and return its stdout, stderr, and exit code."
	override val parameters = listOf(
		ToolParameter("command", "The shell command to execute", type = "string", required = true),
		ToolParameter("workingDir", "Working directory for the command", type = "string", required = false, defaultValue = "."),
		ToolParameter("timeout", "Timeout in seconds (max ${MAX_TIMEOUT_SECONDS}s)", type = "integer", required = false, defaultValue = "30")
	)
	override val enabled: Boolean get() = config.shellEnabled

	companion object {
		const val MAX_TIMEOUT_SECONDS = 120L
		const val MAX_OUTPUT_LENGTH = 50_000

		private val BLOCKED_PATTERNS = listOf(
			Regex("""rm\s+-[^\s]*r[^\s]*f[^\s]*\s+/\s*$"""),
			Regex("""rm\s+-[^\s]*f[^\s]*r[^\s]*\s+/\s*$"""),
			Regex("""mkfs\."""),
			Regex("""dd\s+.*of=/dev/"""),
			Regex(""":[(][)]\s*\{\s*:""")
		)
	}

	override suspend fun execute(arguments: Map<String, String>): ToolResult {
		val command = arguments["command"] ?: return ToolResult(name, false, "", error = "Missing 'command' argument")
		val workingDir = arguments["workingDir"] ?: "."
		val timeoutSeconds = (arguments["timeout"]?.toLongOrNull() ?: 30L).coerceIn(1, MAX_TIMEOUT_SECONDS)

		// Check for dangerous commands
		for (pattern in BLOCKED_PATTERNS) {
			if (pattern.containsMatchIn(command)) {
				logger.warn("Blocked dangerous shell command: $command")
				return ToolResult(name, false, "", error = "Command blocked by safety filter.")
			}
		}

		val dir = File(workingDir)
		if (!dir.exists() || !dir.isDirectory) {
			return ToolResult(name, false, "", error = "Working directory does not exist: $workingDir")
		}

		logger.info("Executing shell command: $command (timeout=${timeoutSeconds}s, dir=$workingDir)")

		return try {
			val process = ProcessBuilder("bash", "-c", command)
				.directory(dir)
				.redirectErrorStream(false)
				.start()

			val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

			if (!completed) {
				process.destroyForcibly()
				return ToolResult(
					toolName = name,
					success = false,
					output = "",
					error = "Command timed out after ${timeoutSeconds}s"
				)
			}

			val stdout = process.inputStream.bufferedReader().readText().take(MAX_OUTPUT_LENGTH)
			val stderr = process.errorStream.bufferedReader().readText().take(MAX_OUTPUT_LENGTH)
			val exitCode = process.exitValue()

			val output = buildString {
				if (stdout.isNotEmpty()) {
					appendLine(stdout.trimEnd())
				}
				if (stderr.isNotEmpty()) {
					appendLine("[stderr]")
					appendLine(stderr.trimEnd())
				}
				appendLine("[exit code: $exitCode]")
			}

			ToolResult(
				toolName = name,
				success = exitCode == 0,
				output = output,
				error = if (exitCode != 0) "Command exited with code $exitCode" else null,
				metadata = mapOf("exitCode" to exitCode.toString(), "workingDir" to workingDir)
			)
		} catch (e: Exception) {
			logger.error("Shell execution failed: ${e.message}", e)
			ToolResult(name, false, "", error = "Execution failed: ${e.message}")
		}
	}
}
