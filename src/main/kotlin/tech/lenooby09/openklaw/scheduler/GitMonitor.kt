package tech.lenooby09.openklaw.scheduler

import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.GitRepoConfig
import tech.lenooby09.openklaw.config.SchedulerConfig
import java.io.File
import java.util.concurrent.*

/**
 * Watches configured Git repositories for changes, monitors builds, parses error logs,
 * and notifies the agent when something needs attention.
 *
 * Uses `git` CLI commands to poll for new commits and detect build status changes.
 * Runs in a background thread with configurable poll interval.
 *
 * Security: Repo paths are validated against allowed base directories. Git commands
 * have a configurable timeout. File reads are bounded.
 */
class GitMonitor(
	private val config: SchedulerConfig,
	private val onGitEvent: suspend (GitEvent) -> Unit
) {
	private val logger = LoggerFactory.getLogger(GitMonitor::class.java)
	private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
		Thread(r, "git-monitor").apply { isDaemon = true }
	}
	private val lastKnownCommits = ConcurrentHashMap<String, String>()
	private val lastBuildStatus = ConcurrentHashMap<String, BuildStatus>()

	/** Dedicated bounded executor for git event task execution. */
	private val taskExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
		1, config.maxConcurrentSchedulerTasks,
		60L, TimeUnit.SECONDS,
		LinkedBlockingQueue(config.maxConcurrentSchedulerTasks * 2)
	)

	@Volatile
	private var running = false

	fun start() {
		if (!config.gitMonitorEnabled) {
			logger.info("Git monitor disabled")
			return
		}
		if (config.gitRepositories.isEmpty()) {
			logger.info("Git monitor enabled but no repositories configured")
			return
		}

		// Validate all repo paths at startup
		for (repo in config.gitRepositories) {
			if (!isPathAllowed(repo.path)) {
				logger.error("Git repo '${repo.name}' path '${repo.path}' is outside allowed base directories: ${config.gitAllowedBaseDirs}. Skipping.")
			}
		}

		running = true

		// Initialize last known state
		for (repo in config.gitRepositories) {
			if (!isPathAllowed(repo.path)) continue
			try {
				val commit = getLatestCommit(repo)
				if (commit != null) {
					lastKnownCommits[repo.name] = commit
				}
			} catch (e: Exception) {
				logger.warn("Failed to initialize git state for ${repo.name}: ${e.message}")
			}
		}

		val intervalMs = config.gitPollIntervalMinutes * 60_000L
		scheduler.scheduleAtFixedRate({
			try {
				pollRepositories()
			} catch (e: Exception) {
				logger.error("Git monitor poll error: ${e.message}", e)
			}
		}, intervalMs, intervalMs, TimeUnit.MILLISECONDS)

		logger.info("Git monitor started — watching ${config.gitRepositories.size} repositories, poll interval=${config.gitPollIntervalMinutes}m")
	}

	fun stop() {
		running = false
		scheduler.shutdown()
		scheduler.awaitTermination(5, TimeUnit.SECONDS)
		taskExecutor.shutdown()
		taskExecutor.awaitTermination(5, TimeUnit.SECONDS)
		logger.info("Git monitor stopped")
	}

	fun getMonitoredRepos(): List<GitRepoConfig> = config.gitRepositories

	fun getRepoCount(): Int = config.gitRepositories.size

	fun getLastCommit(repoName: String): String? = lastKnownCommits[repoName]

	fun getLastBuildStatus(repoName: String): BuildStatus? = lastBuildStatus[repoName]

	/**
	 * Validates that a path is within one of the allowed base directories.
	 * Prevents arbitrary filesystem access via config manipulation.
	 */
	internal fun isPathAllowed(path: String): Boolean {
		if (path.isEmpty()) return false
		val canonical = try {
			File(path).canonicalPath
		} catch (e: Exception) {
			return false
		}
		return config.gitAllowedBaseDirs.any { baseDir ->
			val canonicalBase = try {
				File(baseDir).canonicalPath
			} catch (e: Exception) {
				return@any false
			}
			canonical.startsWith(canonicalBase + File.separator) || canonical == canonicalBase
		}
	}

	private fun pollRepositories() {
		for (repo in config.gitRepositories) {
			if (!isPathAllowed(repo.path)) continue
			try {
				if (repo.watchCommits) {
					checkForNewCommits(repo)
				}
				if (repo.watchBuild) {
					checkBuildStatus(repo)
				}
			} catch (e: Exception) {
				logger.error("Error polling repository ${repo.name}: ${e.message}", e)
			}
		}
	}

	private fun checkForNewCommits(repo: GitRepoConfig) {
		val currentCommit = getLatestCommit(repo) ?: return
		val previousCommit = lastKnownCommits[repo.name]

		if (previousCommit != null && previousCommit != currentCommit) {
			val commitLog = getCommitLog(repo, previousCommit, currentCommit)
			val event = GitEvent(
				repoName = repo.name,
				type = GitEventType.NEW_COMMITS,
				summary = "New commits on ${repo.branch}: $commitLog",
				details = commitLog,
				branch = repo.branch,
				notifyUser = repo.notifyUser,
				timestamp = System.currentTimeMillis()
			)
			fireEvent(event)
		}
		lastKnownCommits[repo.name] = currentCommit
	}

	private fun checkBuildStatus(repo: GitRepoConfig) {
		val buildDir = resolveBuildDir(repo) ?: return
		val status = detectBuildStatus(buildDir)
		val previousStatus = lastBuildStatus[repo.name]

		if (previousStatus != null && previousStatus != status) {
			val event = GitEvent(
				repoName = repo.name,
				type = if (status == BuildStatus.FAILED) GitEventType.BUILD_FAILURE else GitEventType.BUILD_SUCCESS,
				summary = "Build status changed: $previousStatus → $status for ${repo.name}",
				details = if (status == BuildStatus.FAILED) parseBuildErrors(buildDir) else "Build succeeded",
				branch = repo.branch,
				notifyUser = repo.notifyUser,
				timestamp = System.currentTimeMillis()
			)
			fireEvent(event)
		}
		lastBuildStatus[repo.name] = status
	}

	private fun fireEvent(event: GitEvent) {
		try {
			taskExecutor.submit {
				kotlinx.coroutines.runBlocking { onGitEvent(event) }
			}
		} catch (e: RejectedExecutionException) {
			logger.warn("Git event task rejected (executor full) for repo: ${event.repoName}")
		}
		logger.info("Git event: ${event.type} for ${event.repoName} — ${event.summary}")
	}

	internal fun getLatestCommit(repo: GitRepoConfig): String? {
		val dir = resolveRepoDir(repo) ?: return null
		return runGitCommand(dir, "git", "rev-parse", "HEAD")?.trim()
	}

	internal fun getCommitLog(repo: GitRepoConfig, fromCommit: String, toCommit: String): String {
		val dir = resolveRepoDir(repo) ?: return ""
		return runGitCommand(dir, "git", "log", "--oneline", "$fromCommit..$toCommit")?.trim() ?: ""
	}

	private fun resolveRepoDir(repo: GitRepoConfig): File? {
		if (repo.path.isNotEmpty() && isPathAllowed(repo.path)) {
			val dir = File(repo.path)
			if (dir.exists() && File(dir, ".git").exists()) return dir
		}
		return null
	}

	private fun resolveBuildDir(repo: GitRepoConfig): File? {
		val repoDir = resolveRepoDir(repo) ?: return null
		val candidates = listOf("build", "target", "dist", "out")
		for (candidate in candidates) {
			val dir = File(repoDir, candidate)
			if (dir.exists()) return dir
		}
		return repoDir
	}

	internal fun detectBuildStatus(buildDir: File): BuildStatus {
		val maxBytes = config.gitMaxFileReadBytes
		val errorFiles = listOf("build.log", "error.log", "build-error.log")
		for (errorFile in errorFiles) {
			val file = File(buildDir, errorFile)
			if (file.exists()) {
				val content = readBounded(file, maxBytes).lowercase()
				if (content.contains("error") || content.contains("failed") || content.contains("failure")) {
					return BuildStatus.FAILED
				}
			}
		}

		val gradleReports = File(buildDir, "reports")
		if (gradleReports.exists()) {
			val testResults = File(gradleReports, "tests/test/index.html")
			if (testResults.exists()) {
				val content = readBounded(testResults, maxBytes).lowercase()
				if (content.contains("failures") && !content.contains("0 failures")) {
					return BuildStatus.FAILED
				}
			}
		}

		return BuildStatus.SUCCESS
	}

	internal fun parseBuildErrors(buildDir: File): String {
		val maxBytes = config.gitMaxFileReadBytes
		val errorLines = mutableListOf<String>()
		val errorFiles = listOf("build.log", "error.log", "build-error.log")

		for (errorFile in errorFiles) {
			val file = File(buildDir, errorFile)
			if (file.exists()) {
				readBounded(file, maxBytes).lines()
					.filter { line ->
						val lower = line.lowercase()
						lower.contains("error") || lower.contains("failed") || lower.contains("exception")
					}
					.take(20)
					.forEach { errorLines.add(it) }
			}
		}

		return if (errorLines.isNotEmpty()) {
			errorLines.joinToString("\n")
		} else {
			"Build failed — no detailed error log found"
		}
	}

	/**
	 * Read a file with a bounded size limit to prevent memory exhaustion.
	 */
	private fun readBounded(file: File, maxBytes: Long): String {
		if (file.length() > maxBytes) {
			logger.warn("File ${file.path} exceeds max read size (${file.length()} > $maxBytes), reading partial")
		}
		return file.inputStream().use { input ->
			val bytes = ByteArray(minOf(file.length(), maxBytes).toInt())
			val read = input.read(bytes)
			if (read > 0) String(bytes, 0, read) else ""
		}
	}

	/**
	 * Run a git command with a configurable timeout.
	 * Returns null if the command fails or times out.
	 */
	private fun runGitCommand(dir: File, vararg command: String): String? {
		return try {
			val process = ProcessBuilder(*command)
				.directory(dir)
				.redirectErrorStream(true)
				.start()
			val output = process.inputStream.bufferedReader().readText()
			val completed = process.waitFor(config.gitCommandTimeoutSeconds, TimeUnit.SECONDS)
			if (!completed) {
				process.destroyForcibly()
				logger.warn("Git command timed out after ${config.gitCommandTimeoutSeconds}s in ${dir.path}: ${command.joinToString(" ")}")
				return null
			}
			if (process.exitValue() == 0) output else null
		} catch (e: Exception) {
			logger.debug("Git command failed in ${dir.path}: ${e.message}")
			null
		}
	}
}

data class GitEvent(
	val repoName: String,
	val type: GitEventType,
	val summary: String,
	val details: String,
	val branch: String,
	val notifyUser: String,
	val timestamp: Long
)

enum class GitEventType {
	NEW_COMMITS, BUILD_FAILURE, BUILD_SUCCESS
}

enum class BuildStatus {
	SUCCESS, FAILED, UNKNOWN
}
