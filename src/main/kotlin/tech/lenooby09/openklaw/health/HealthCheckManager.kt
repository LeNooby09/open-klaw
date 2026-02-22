package tech.lenooby09.openklaw.health

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Health Checks & Doctor Diagnostics — self-diagnosis tools to detect misconfiguration or degraded state.
 *
 * Aggregates health status from all subsystems (LLM providers, tools, memory, channels, schedulers)
 * into a unified health report. Supports individual and aggregate health checks.
 */
class HealthCheckManager {
	private val logger = LoggerFactory.getLogger(HealthCheckManager::class.java)
	private val checks = CopyOnWriteArrayList<HealthCheck>()

	/**
	 * Register a health check.
	 */
	fun register(check: HealthCheck) {
		checks.add(check)
		logger.debug("Registered health check: ${check.name}")
	}

	/**
	 * Run all registered health checks and return an aggregate report.
	 */
	suspend fun runAll(): HealthReport {
		val results = checks.map { check ->
			try {
				check.execute()
			} catch (e: Exception) {
				logger.error("Health check '${check.name}' threw exception: ${e.message}", e)
				HealthCheckResult(
					name = check.name,
					status = HealthStatus.UNHEALTHY,
					message = "Check threw exception: ${e.message}",
					details = mapOf("exception" to (e.javaClass.simpleName))
				)
			}
		}

		val overallStatus = when {
			results.all { it.status == HealthStatus.HEALTHY } -> HealthStatus.HEALTHY
			results.any { it.status == HealthStatus.UNHEALTHY } -> HealthStatus.UNHEALTHY
			else -> HealthStatus.DEGRADED
		}

		return HealthReport(
			status = overallStatus,
			checks = results,
			timestamp = System.currentTimeMillis()
		)
	}

	/**
	 * Run a single named health check.
	 */
	suspend fun runCheck(name: String): HealthCheckResult? {
		val check = checks.find { it.name == name } ?: return null
		return try {
			check.execute()
		} catch (e: Exception) {
			HealthCheckResult(
				name = check.name,
				status = HealthStatus.UNHEALTHY,
				message = "Check threw exception: ${e.message}"
			)
		}
	}

	/**
	 * Run doctor diagnostics — a comprehensive report with recommendations.
	 */
	suspend fun runDiagnostics(): DiagnosticsReport {
		val healthReport = runAll()
		val issues = mutableListOf<DiagnosticIssue>()

		for (result in healthReport.checks) {
			if (result.status != HealthStatus.HEALTHY) {
				issues.add(
					DiagnosticIssue(
						component = result.name,
						severity = if (result.status == HealthStatus.UNHEALTHY) "ERROR" else "WARNING",
						message = result.message,
						recommendation = result.details["recommendation"]?.toString()
							?: generateRecommendation(result.name, result.status)
					)
				)
			}
		}

		return DiagnosticsReport(
			overallStatus = healthReport.status,
			issues = issues,
			healthyComponents = healthReport.checks.count { it.status == HealthStatus.HEALTHY },
			totalComponents = healthReport.checks.size,
			timestamp = System.currentTimeMillis()
		)
	}

	fun getCheckCount(): Int = checks.size

	private fun generateRecommendation(name: String, status: HealthStatus): String {
		return when {
			name.contains("llm", ignoreCase = true) -> "Check LLM provider configuration and API keys."
			name.contains("memory", ignoreCase = true) -> "Verify data directory permissions and disk space."
			name.contains("tool", ignoreCase = true) -> "Ensure required tools are registered and enabled."
			name.contains("channel", ignoreCase = true) -> "Check messaging channel credentials and connectivity."
			name.contains("scheduler", ignoreCase = true) -> "Review scheduler configuration and task definitions."
			status == HealthStatus.UNHEALTHY -> "Investigate the component logs for error details."
			else -> "Monitor this component for further degradation."
		}
	}
}

/**
 * Interface for individual health checks.
 */
interface HealthCheck {
	val name: String
	suspend fun execute(): HealthCheckResult
}

enum class HealthStatus { HEALTHY, DEGRADED, UNHEALTHY }

@Serializable
data class HealthCheckResult(
	val name: String,
	val status: HealthStatus,
	val message: String,
	val details: Map<String, String> = emptyMap()
)

@Serializable
data class HealthReport(
	val status: HealthStatus,
	val checks: List<HealthCheckResult>,
	val timestamp: Long
)

@Serializable
data class DiagnosticIssue(
	val component: String,
	val severity: String,
	val message: String,
	val recommendation: String
)

@Serializable
data class DiagnosticsReport(
	val overallStatus: HealthStatus,
	val issues: List<DiagnosticIssue>,
	val healthyComponents: Int,
	val totalComponents: Int,
	val timestamp: Long
)

/**
 * Built-in health checks for core subsystems.
 */
object BuiltInHealthChecks {

	/** Check that at least one LLM provider is configured and available. */
	class LlmHealthCheck(
		private val providerCount: () -> Int,
		private val healthCheck: suspend () -> Map<String, Boolean>
	) : HealthCheck {
		override val name = "llm_providers"
		override suspend fun execute(): HealthCheckResult {
			val count = providerCount()
			if (count == 0) {
				return HealthCheckResult(name, HealthStatus.UNHEALTHY, "No LLM providers configured.",
					mapOf("recommendation" to "Configure at least one LLM provider in the config file."))
			}
			val statuses = healthCheck()
			val healthy = statuses.count { it.value }
			return when {
				healthy == count -> HealthCheckResult(name, HealthStatus.HEALTHY, "$count provider(s) healthy.")
				healthy > 0 -> HealthCheckResult(name, HealthStatus.DEGRADED, "$healthy/$count provider(s) healthy.",
					mapOf("unhealthy" to statuses.filter { !it.value }.keys.joinToString()))
				else -> HealthCheckResult(name, HealthStatus.UNHEALTHY, "All $count provider(s) unhealthy.",
					mapOf("recommendation" to "Check API keys and network connectivity."))
			}
		}
	}

	/** Check tool registry status. */
	class ToolRegistryHealthCheck(
		private val toolCount: () -> Int,
		private val enabledCount: () -> Int
	) : HealthCheck {
		override val name = "tool_registry"
		override suspend fun execute(): HealthCheckResult {
			val total = toolCount()
			val enabled = enabledCount()
			return when {
				total == 0 -> HealthCheckResult(name, HealthStatus.DEGRADED, "No tools registered.",
					mapOf("recommendation" to "Register tools to enable agent capabilities."))
				enabled == 0 -> HealthCheckResult(name, HealthStatus.DEGRADED, "$total tool(s) registered but none enabled.")
				else -> HealthCheckResult(name, HealthStatus.HEALTHY, "$enabled/$total tool(s) enabled.")
			}
		}
	}

	/** Check memory system status. */
	class MemoryHealthCheck(
		private val dataDir: String,
		private val soulFileExists: () -> Boolean,
		private val memoryFileExists: () -> Boolean
	) : HealthCheck {
		override val name = "memory_system"
		override suspend fun execute(): HealthCheckResult {
			val dir = java.io.File(dataDir)
			val issues = mutableListOf<String>()

			if (!dir.exists()) issues.add("Data directory does not exist: $dataDir")
			else if (!dir.canWrite()) issues.add("Data directory is not writable: $dataDir")

			if (!soulFileExists()) issues.add("SOUL.md not found")
			if (!memoryFileExists()) issues.add("MEMORY.md not found")

			return when {
				issues.isEmpty() -> HealthCheckResult(name, HealthStatus.HEALTHY, "Memory system operational.")
				issues.any { it.contains("not writable") || it.contains("does not exist") } ->
					HealthCheckResult(name, HealthStatus.UNHEALTHY, issues.joinToString("; "),
						mapOf("recommendation" to "Ensure data directory exists and is writable."))
				else -> HealthCheckResult(name, HealthStatus.DEGRADED, issues.joinToString("; "),
					mapOf("recommendation" to "Missing memory files will be auto-created on next use."))
			}
		}
	}

	/** Check messaging channels status. */
	class ChannelHealthCheck(
		private val channelCount: () -> Int,
		private val channelStatuses: () -> Map<String, Boolean>
	) : HealthCheck {
		override val name = "messaging_channels"
		override suspend fun execute(): HealthCheckResult {
			val count = channelCount()
			if (count == 0) {
				return HealthCheckResult(name, HealthStatus.HEALTHY, "No messaging channels configured (optional).")
			}
			val statuses = channelStatuses()
			val running = statuses.count { it.value }
			return when {
				running == count -> HealthCheckResult(name, HealthStatus.HEALTHY, "$count channel(s) running.")
				running > 0 -> HealthCheckResult(name, HealthStatus.DEGRADED, "$running/$count channel(s) running.",
					mapOf("stopped" to statuses.filter { !it.value }.keys.joinToString()))
				else -> HealthCheckResult(name, HealthStatus.UNHEALTHY, "All $count channel(s) stopped.",
					mapOf("recommendation" to "Check channel credentials and network connectivity."))
			}
		}
	}

	/** System resource check. */
	class SystemResourceHealthCheck : HealthCheck {
		override val name = "system_resources"
		override suspend fun execute(): HealthCheckResult {
			val runtime = Runtime.getRuntime()
			val maxMemory = runtime.maxMemory()
			val totalMemory = runtime.totalMemory()
			val freeMemory = runtime.freeMemory()
			val usedMemory = totalMemory - freeMemory
			val memoryUsagePercent = (usedMemory * 100.0 / maxMemory).toInt()

			val details = mapOf(
				"maxMemoryMb" to "${maxMemory / 1024 / 1024}",
				"usedMemoryMb" to "${usedMemory / 1024 / 1024}",
				"freeMemoryMb" to "${freeMemory / 1024 / 1024}",
				"memoryUsagePercent" to "$memoryUsagePercent",
				"availableProcessors" to "${runtime.availableProcessors()}"
			)

			return when {
				memoryUsagePercent > 90 -> HealthCheckResult(name, HealthStatus.UNHEALTHY,
					"Memory usage critical: $memoryUsagePercent%", details + ("recommendation" to "Increase JVM heap size or reduce load."))
				memoryUsagePercent > 75 -> HealthCheckResult(name, HealthStatus.DEGRADED,
					"Memory usage high: $memoryUsagePercent%", details)
				else -> HealthCheckResult(name, HealthStatus.HEALTHY,
					"Memory usage: $memoryUsagePercent%", details)
			}
		}
	}
}
