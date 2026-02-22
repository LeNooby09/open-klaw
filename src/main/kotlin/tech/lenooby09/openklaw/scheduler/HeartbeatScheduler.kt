package tech.lenooby09.openklaw.scheduler

import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.SchedulerConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.*

/**
 * Parses HEARTBEAT.md and periodically wakes the agent according to its schedule.
 *
 * HEARTBEAT.md format:
 * ```
 * # Heartbeat Schedule
 *
 * ## Rules
 * - every 30m: Check system health
 * - daily 09:00: Morning summary
 * - weekday 17:00: End-of-day report
 * - weekend 10:00: Weekend check-in
 * - hourly: Inbox check
 * ```
 *
 * Security: HEARTBEAT.md is written with owner-only permissions. Rule IDs use SHA-256
 * for collision resistance.
 */
class HeartbeatScheduler(
	private val config: SchedulerConfig,
	private val dataDir: String = "data",
	private val onHeartbeat: suspend (HeartbeatTask) -> Unit
) {
	private val logger = LoggerFactory.getLogger(HeartbeatScheduler::class.java)
	private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
		Thread(r, "heartbeat-scheduler").apply { isDaemon = true }
	}
	private val rules = CopyOnWriteArrayList<HeartbeatRule>()
	private val lastExecuted = ConcurrentHashMap<String, Long>()

	/** Dedicated bounded executor for heartbeat task execution. */
	private val taskExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
		1, config.maxConcurrentSchedulerTasks,
		60L, TimeUnit.SECONDS,
		LinkedBlockingQueue(config.maxConcurrentSchedulerTasks * 2)
	)

	@Volatile
	private var running = false

	fun start() {
		if (!config.heartbeatEnabled) {
			logger.info("Heartbeat scheduler disabled")
			return
		}
		running = true
		loadHeartbeatFile()

		val intervalMs = config.heartbeatCheckIntervalMinutes * 60_000L
		scheduler.scheduleAtFixedRate({
			try {
				checkAndExecute()
			} catch (e: Exception) {
				logger.error("Heartbeat check error: ${e.message}", e)
			}
		}, intervalMs, intervalMs, TimeUnit.MILLISECONDS)

		logger.info("Heartbeat scheduler started — ${rules.size} rules loaded, check interval=${config.heartbeatCheckIntervalMinutes}m")
	}

	fun stop() {
		running = false
		scheduler.shutdown()
		scheduler.awaitTermination(5, TimeUnit.SECONDS)
		taskExecutor.shutdown()
		taskExecutor.awaitTermination(5, TimeUnit.SECONDS)
		logger.info("Heartbeat scheduler stopped")
	}

	fun reload() {
		rules.clear()
		loadHeartbeatFile()
		logger.info("Heartbeat rules reloaded — ${rules.size} rules")
	}

	fun getRules(): List<HeartbeatRule> = rules.toList()

	fun getRuleCount(): Int = rules.size

	private fun loadHeartbeatFile() {
		val file = File(dataDir, config.heartbeatFile)
		if (!file.exists()) {
			createDefaultHeartbeatFile(file)
		}
		val content = file.readText()
		val parsed = parseHeartbeatRules(content)
		rules.addAll(parsed)
	}

	private fun createDefaultHeartbeatFile(file: File) {
		file.parentFile?.mkdirs()
		file.writeText(DEFAULT_HEARTBEAT)
		setRestrictivePermissions(file)
		logger.info("Created default ${config.heartbeatFile}")
	}

	/**
	 * Set owner-only read/write permissions on the file.
	 */
	private fun setRestrictivePermissions(file: File) {
		try {
			val perms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
			Files.setPosixFilePermissions(file.toPath(), perms)
		} catch (e: UnsupportedOperationException) {
			// Non-POSIX filesystem (e.g., Windows) — skip
		} catch (e: Exception) {
			logger.warn("Could not set restrictive permissions on ${file.path}: ${e.message}")
		}
	}

	private fun checkAndExecute() {
		val now = LocalDateTime.now()
		for (rule in rules) {
			if (shouldExecute(rule, now)) {
				lastExecuted[rule.id] = System.currentTimeMillis()
				val task = HeartbeatTask(
					ruleId = rule.id,
					description = rule.description,
					triggeredAt = System.currentTimeMillis()
				)
				try {
					taskExecutor.submit {
						kotlinx.coroutines.runBlocking { onHeartbeat(task) }
					}
				} catch (e: RejectedExecutionException) {
					logger.warn("Heartbeat task rejected (executor full) for rule: ${rule.description}")
				}
				logger.info("Heartbeat triggered: ${rule.description} (${rule.schedule})")
			}
		}
	}

	internal fun shouldExecute(rule: HeartbeatRule, now: LocalDateTime): Boolean {
		val lastRun = lastExecuted[rule.id]
		return when (rule.schedule) {
			is HeartbeatSchedule.EveryMinutes -> {
				val intervalMs = rule.schedule.minutes * 60_000L
				lastRun == null || (System.currentTimeMillis() - lastRun) >= intervalMs
			}
			is HeartbeatSchedule.Hourly -> {
				lastRun == null || (System.currentTimeMillis() - lastRun) >= 3_600_000L
			}
			is HeartbeatSchedule.DailyAt -> {
				val targetTime = rule.schedule.time
				val checkWindow = config.heartbeatCheckIntervalMinutes.toLong()
				now.toLocalTime() >= targetTime &&
					now.toLocalTime() < targetTime.plusMinutes(checkWindow) &&
					(lastRun == null || (System.currentTimeMillis() - lastRun) >= 3_600_000L)
			}
			is HeartbeatSchedule.WeekdayAt -> {
				val dow = now.dayOfWeek
				val isWeekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY
				if (!isWeekday) return false
				val targetTime = rule.schedule.time
				val checkWindow = config.heartbeatCheckIntervalMinutes.toLong()
				now.toLocalTime() >= targetTime &&
					now.toLocalTime() < targetTime.plusMinutes(checkWindow) &&
					(lastRun == null || (System.currentTimeMillis() - lastRun) >= 3_600_000L)
			}
			is HeartbeatSchedule.WeekendAt -> {
				val dow = now.dayOfWeek
				val isWeekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
				if (!isWeekend) return false
				val targetTime = rule.schedule.time
				val checkWindow = config.heartbeatCheckIntervalMinutes.toLong()
				now.toLocalTime() >= targetTime &&
					now.toLocalTime() < targetTime.plusMinutes(checkWindow) &&
					(lastRun == null || (System.currentTimeMillis() - lastRun) >= 3_600_000L)
			}
		}
	}

	companion object {
		private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

		val DEFAULT_HEARTBEAT = """
			|# Heartbeat Schedule
			|
			|> Define periodic wake-up rules for the agent. The agent will execute these tasks
			|> automatically without being prompted. Edit this file to customize the schedule.
			|
			|## Rules
			|- every 60m: System health check
			|- daily 09:00: Morning summary and task review
		""".trimMargin()

		/**
		 * Generate a collision-resistant ID using SHA-256 truncated to 16 hex chars.
		 */
		private fun generateRuleId(schedulePart: String, description: String): String {
			val input = "$schedulePart:$description"
			val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
			val hash = digest.take(8).joinToString("") { "%02x".format(it) }
			return "${schedulePart.replace("\\s+".toRegex(), "_")}_$hash"
		}

		/**
		 * Parses heartbeat rules from HEARTBEAT.md content.
		 * Supported formats:
		 * - `every Nm:` — every N minutes
		 * - `hourly:` — once per hour
		 * - `daily HH:mm:` — daily at specified time
		 * - `weekday HH:mm:` — weekdays only at specified time
		 * - `weekend HH:mm:` — weekends only at specified time
		 */
		fun parseHeartbeatRules(content: String): List<HeartbeatRule> {
			val rules = mutableListOf<HeartbeatRule>()
			val ruleRegex = Regex("^-\\s+(every\\s+\\d+m|hourly|daily\\s+\\d{2}:\\d{2}|weekday\\s+\\d{2}:\\d{2}|weekend\\s+\\d{2}:\\d{2}):\\s*(.+)$")

			for (line in content.lines()) {
				val trimmed = line.trim()
				val match = ruleRegex.matchEntire(trimmed) ?: continue
				val schedulePart = match.groupValues[1].trim()
				val description = match.groupValues[2].trim()

				val schedule = parseSchedule(schedulePart) ?: continue
				val id = generateRuleId(schedulePart, description)
				rules.add(HeartbeatRule(id = id, schedule = schedule, description = description, rawLine = trimmed))
			}
			return rules
		}

		private fun parseSchedule(text: String): HeartbeatSchedule? {
			return when {
				text.startsWith("every ") -> {
					val minutes = text.removePrefix("every ").removeSuffix("m").toIntOrNull() ?: return null
					if (minutes < 1) return null
					HeartbeatSchedule.EveryMinutes(minutes)
				}
				text == "hourly" -> HeartbeatSchedule.Hourly
				text.startsWith("daily ") -> {
					val time = parseTime(text.removePrefix("daily ")) ?: return null
					HeartbeatSchedule.DailyAt(time)
				}
				text.startsWith("weekday ") -> {
					val time = parseTime(text.removePrefix("weekday ")) ?: return null
					HeartbeatSchedule.WeekdayAt(time)
				}
				text.startsWith("weekend ") -> {
					val time = parseTime(text.removePrefix("weekend ")) ?: return null
					HeartbeatSchedule.WeekendAt(time)
				}
				else -> null
			}
		}

		private fun parseTime(text: String): LocalTime? {
			return try {
				LocalTime.parse(text.trim(), TIME_FORMAT)
			} catch (e: Exception) {
				null
			}
		}
	}
}

data class HeartbeatTask(
	val ruleId: String,
	val description: String,
	val triggeredAt: Long
)

data class HeartbeatRule(
	val id: String,
	val schedule: HeartbeatSchedule,
	val description: String,
	val rawLine: String
)

sealed class HeartbeatSchedule {
	data class EveryMinutes(val minutes: Int) : HeartbeatSchedule()
	data object Hourly : HeartbeatSchedule()
	data class DailyAt(val time: LocalTime) : HeartbeatSchedule()
	data class WeekdayAt(val time: LocalTime) : HeartbeatSchedule()
	data class WeekendAt(val time: LocalTime) : HeartbeatSchedule()
}
