package tech.lenooby09.openklaw.scheduler

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.SchedulerConfig
import java.time.LocalDateTime
import java.util.concurrent.*

/**
 * Schedules recurring tasks using cron-like expressions.
 *
 * Supports a simplified cron format: `minute hour dayOfMonth month dayOfWeek`
 * - `*` matches any value
 * - Numeric values match exactly
 * - `start/step` for step intervals (e.g., `0/15` = every 15 starting at 0)
 * - Comma-separated lists (e.g., `1,3,5`)
 */
class CronScheduler(
	private val config: SchedulerConfig,
	private val onCronTrigger: suspend (CronJob) -> Unit
) {
	private val logger = LoggerFactory.getLogger(CronScheduler::class.java)
	private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
		Thread(r, "cron-scheduler").apply { isDaemon = true }
	}
	private val jobs = ConcurrentHashMap<String, CronJob>()
	private val lastExecuted = ConcurrentHashMap<String, Long>()

	/** Dedicated bounded executor for cron task execution (prevents ForkJoinPool starvation). */
	private val taskExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
		1, config.maxConcurrentSchedulerTasks,
		60L, TimeUnit.SECONDS,
		LinkedBlockingQueue(config.maxConcurrentSchedulerTasks * 2)
	)

	@Volatile
	private var running = false

	fun start() {
		if (!config.cronEnabled) {
			logger.info("Cron scheduler disabled")
			return
		}
		running = true

		scheduler.scheduleAtFixedRate({
			try {
				checkAndExecute()
			} catch (e: Exception) {
				logger.error("Cron check error: ${e.message}", e)
			}
		}, 60_000L, 60_000L, TimeUnit.MILLISECONDS)

		logger.info("Cron scheduler started — ${jobs.size} jobs loaded")
	}

	fun stop() {
		running = false
		scheduler.shutdown()
		scheduler.awaitTermination(5, TimeUnit.SECONDS)
		taskExecutor.shutdown()
		taskExecutor.awaitTermination(5, TimeUnit.SECONDS)
		logger.info("Cron scheduler stopped")
	}

	fun addJob(job: CronJob): Boolean {
		if (jobs.size >= config.maxCronJobs) {
			logger.warn("Max cron jobs reached (${config.maxCronJobs}), cannot add: ${job.name}")
			return false
		}
		if (!SAFE_ID_REGEX.matches(job.id)) {
			logger.warn("Invalid cron job ID format: ${job.id}")
			return false
		}
		if (!CronExpression.isValid(job.expression)) {
			logger.warn("Invalid cron expression for job ${job.name}: ${job.expression}")
			return false
		}
		if (!SAFE_USERNAME_REGEX.matches(job.username)) {
			logger.warn("Invalid username for cron job ${job.name}: ${job.username}")
			return false
		}
		jobs[job.id] = job
		logger.info("Cron job added: ${job.name} (${job.expression})")
		return true
	}

	fun removeJob(jobId: String): Boolean {
		val removed = jobs.remove(jobId)
		lastExecuted.remove(jobId)
		if (removed != null) {
			logger.info("Cron job removed: ${removed.name}")
		}
		return removed != null
	}

	fun getJob(jobId: String): CronJob? = jobs[jobId]

	fun listJobs(): List<CronJob> = jobs.values.toList()

	fun getJobCount(): Int = jobs.size

	private fun checkAndExecute() {
		val now = LocalDateTime.now()
		for ((id, job) in jobs) {
			if (!job.enabled) continue
			val expr = CronExpression.parse(job.expression) ?: continue
			if (expr.matches(now)) {
				val lastRun = lastExecuted[id]
				// Prevent double-fire within the same minute
				if (lastRun != null && (System.currentTimeMillis() - lastRun) < 60_000L) continue

				lastExecuted[id] = System.currentTimeMillis()
				try {
					taskExecutor.submit {
						kotlinx.coroutines.runBlocking { onCronTrigger(job) }
					}
				} catch (e: RejectedExecutionException) {
					logger.warn("Cron task rejected (executor full) for job: ${job.name}")
				}
				logger.info("Cron job triggered: ${job.name} (${job.expression})")
			}
		}
	}

	companion object {
		private val SAFE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
		private val SAFE_USERNAME_REGEX = Regex("^(system|[a-zA-Z0-9_-]{3,32})$")
	}
}

@Serializable
data class CronJob(
	val id: String,
	val name: String,
	val expression: String,
	val taskDescription: String,
	val username: String = "system",
	val enabled: Boolean = true,
	val createdByAdmin: Boolean = false,
	val createdAt: Long = System.currentTimeMillis()
)

/**
 * Simplified cron expression parser: `minute hour dayOfMonth month dayOfWeek`
 */
data class CronExpression(
	val minute: CronField,
	val hour: CronField,
	val dayOfMonth: CronField,
	val month: CronField,
	val dayOfWeek: CronField
) {
	fun matches(dt: LocalDateTime): Boolean {
		return minute.matches(dt.minute) &&
			hour.matches(dt.hour) &&
			dayOfMonth.matches(dt.dayOfMonth) &&
			month.matches(dt.monthValue) &&
			dayOfWeek.matches(dt.dayOfWeek.value % 7) // Sunday = 0
	}

	companion object {
		fun parse(expression: String): CronExpression? {
			val parts = expression.trim().split("\\s+".toRegex())
			if (parts.size != 5) return null
			return try {
				CronExpression(
					minute = CronField.parse(parts[0], 0, 59),
					hour = CronField.parse(parts[1], 0, 23),
					dayOfMonth = CronField.parse(parts[2], 1, 31),
					month = CronField.parse(parts[3], 1, 12),
					dayOfWeek = CronField.parse(parts[4], 0, 6)
				)
			} catch (e: Exception) {
				null
			}
		}

		fun isValid(expression: String): Boolean = parse(expression) != null
	}
}

sealed class CronField {
	data object Any : CronField() {
		override fun matches(value: Int) = true
	}

	data class Exact(val v: Int) : CronField() {
		override fun matches(value: Int) = value == v
	}

	data class Step(val start: Int, val step: Int) : CronField() {
		override fun matches(value: Int) = value >= start && (value - start) % step == 0
	}

	data class List(val values: Set<Int>) : CronField() {
		override fun matches(value: Int) = value in values
	}

	abstract fun matches(value: Int): Boolean

	companion object {
		fun parse(field: String, minVal: Int, maxVal: Int): CronField {
			if (field == "*") return Any

			if (field.contains("/")) {
				val parts = field.split("/")
				val start = if (parts[0] == "*") minVal else parts[0].toInt()
				val step = parts[1].toInt()
				require(step > 0) { "Step must be > 0" }
				require(start in minVal..maxVal) { "Start $start out of range $minVal..$maxVal" }
				return Step(start, step)
			}

			if (field.contains(",")) {
				val values = field.split(",").map {
					val v = it.trim().toInt()
					require(v in minVal..maxVal) { "Value $v out of range $minVal..$maxVal" }
					v
				}.toSet()
				return List(values)
			}

			val v = field.toInt()
			require(v in minVal..maxVal) { "Value $v out of range $minVal..$maxVal" }
			return Exact(v)
		}
	}
}
