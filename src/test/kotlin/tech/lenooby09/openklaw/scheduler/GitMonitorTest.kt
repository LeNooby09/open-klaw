package tech.lenooby09.openklaw.scheduler

import tech.lenooby09.openklaw.config.GitRepoConfig
import tech.lenooby09.openklaw.config.SchedulerConfig
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class GitMonitorTest {

	@Test
	fun `disabled monitor does not start`() {
		val config = SchedulerConfig(gitMonitorEnabled = false)
		val monitor = GitMonitor(config) { }
		monitor.start()
		assertEquals(0, monitor.getRepoCount())
		monitor.stop()
	}

	@Test
	fun `getMonitoredRepos returns configured repos`() {
		val repos = listOf(
			GitRepoConfig(name = "repo1", path = "/tmp/repo1"),
			GitRepoConfig(name = "repo2", path = "/tmp/repo2")
		)
		val config = SchedulerConfig(gitMonitorEnabled = true, gitRepositories = repos)
		val monitor = GitMonitor(config) { }
		assertEquals(2, monitor.getRepoCount())
		assertEquals(2, monitor.getMonitoredRepos().size)
	}

	@Test
	fun `detectBuildStatus returns SUCCESS for clean directory`() {
		val tempDir = createTempDirectory("git-build-test").toFile()
		try {
			val config = SchedulerConfig(gitMonitorEnabled = true)
			val monitor = GitMonitor(config) { }
			assertEquals(BuildStatus.SUCCESS, monitor.detectBuildStatus(tempDir))
		} finally {
			tempDir.deleteRecursively()
		}
	}

	@Test
	fun `detectBuildStatus returns FAILED when error log contains errors`() {
		val tempDir = createTempDirectory("git-build-fail").toFile()
		try {
			File(tempDir, "build.log").writeText("COMPILATION ERROR: Something failed\nBuild FAILED")
			val config = SchedulerConfig(gitMonitorEnabled = true)
			val monitor = GitMonitor(config) { }
			assertEquals(BuildStatus.FAILED, monitor.detectBuildStatus(tempDir))
		} finally {
			tempDir.deleteRecursively()
		}
	}

	@Test
	fun `parseBuildErrors extracts error lines from log`() {
		val tempDir = createTempDirectory("git-parse-errors").toFile()
		try {
			File(tempDir, "build.log").writeText(
				"Starting build...\nCOMPILATION ERROR at line 42\nProcessing...\nBuild FAILED\nDone."
			)
			val config = SchedulerConfig(gitMonitorEnabled = true)
			val monitor = GitMonitor(config) { }
			val errors = monitor.parseBuildErrors(tempDir)
			assertTrue(errors.contains("ERROR"))
			assertTrue(errors.contains("FAILED"))
		} finally {
			tempDir.deleteRecursively()
		}
	}

	@Test
	fun `parseBuildErrors returns fallback when no log files`() {
		val tempDir = createTempDirectory("git-no-logs").toFile()
		try {
			val config = SchedulerConfig(gitMonitorEnabled = true)
			val monitor = GitMonitor(config) { }
			val errors = monitor.parseBuildErrors(tempDir)
			assertTrue(errors.contains("no detailed error log found"))
		} finally {
			tempDir.deleteRecursively()
		}
	}

	@Test
	fun `getLatestCommit returns null for non-git directory`() {
		val tempDir = createTempDirectory("git-no-repo").toFile()
		try {
			val config = SchedulerConfig(gitMonitorEnabled = true)
			val monitor = GitMonitor(config) { }
			val repo = GitRepoConfig(name = "test", path = tempDir.absolutePath)
			assertNull(monitor.getLatestCommit(repo))
		} finally {
			tempDir.deleteRecursively()
		}
	}

	@Test
	fun `getLastCommit returns null when not initialized`() {
		val config = SchedulerConfig(gitMonitorEnabled = true)
		val monitor = GitMonitor(config) { }
		assertNull(monitor.getLastCommit("nonexistent"))
	}

	@Test
	fun `getLastBuildStatus returns null when not initialized`() {
		val config = SchedulerConfig(gitMonitorEnabled = true)
		val monitor = GitMonitor(config) { }
		assertNull(monitor.getLastBuildStatus("nonexistent"))
	}
}
