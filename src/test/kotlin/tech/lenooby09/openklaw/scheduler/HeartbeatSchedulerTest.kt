package tech.lenooby09.openklaw.scheduler

import tech.lenooby09.openklaw.config.SchedulerConfig
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class HeartbeatSchedulerTest {

	@Test
	fun `parse heartbeat rules from content`() {
		val content = """
			# Heartbeat Schedule
			
			## Rules
			- every 30m: Check system health
			- daily 09:00: Morning summary
			- weekday 17:00: End-of-day report
			- weekend 10:00: Weekend check-in
			- hourly: Inbox check
		""".trimIndent()

		val rules = HeartbeatScheduler.parseHeartbeatRules(content)
		assertEquals(5, rules.size)

		assertTrue(rules[0].schedule is HeartbeatSchedule.EveryMinutes)
		assertEquals(30, (rules[0].schedule as HeartbeatSchedule.EveryMinutes).minutes)
		assertEquals("Check system health", rules[0].description)

		assertTrue(rules[1].schedule is HeartbeatSchedule.DailyAt)
		assertEquals(LocalTime.of(9, 0), (rules[1].schedule as HeartbeatSchedule.DailyAt).time)

		assertTrue(rules[2].schedule is HeartbeatSchedule.WeekdayAt)
		assertEquals(LocalTime.of(17, 0), (rules[2].schedule as HeartbeatSchedule.WeekdayAt).time)

		assertTrue(rules[3].schedule is HeartbeatSchedule.WeekendAt)
		assertEquals(LocalTime.of(10, 0), (rules[3].schedule as HeartbeatSchedule.WeekendAt).time)

		assertTrue(rules[4].schedule is HeartbeatSchedule.Hourly)
	}

	@Test
	fun `parse ignores non-rule lines`() {
		val content = """
			# Heartbeat Schedule
			Some random text
			- not a rule
			- every 15m: Valid rule
			> A blockquote
		""".trimIndent()

		val rules = HeartbeatScheduler.parseHeartbeatRules(content)
		assertEquals(1, rules.size)
		assertEquals("Valid rule", rules[0].description)
	}

	@Test
	fun `parse empty content returns empty list`() {
		val rules = HeartbeatScheduler.parseHeartbeatRules("")
		assertTrue(rules.isEmpty())
	}

	@Test
	fun `parse invalid time format is skipped`() {
		val content = "- daily 25:00: Bad time"
		val rules = HeartbeatScheduler.parseHeartbeatRules(content)
		assertTrue(rules.isEmpty())
	}

	@Test
	fun `parse every 0m is skipped`() {
		val content = "- every 0m: Invalid interval"
		val rules = HeartbeatScheduler.parseHeartbeatRules(content)
		assertTrue(rules.isEmpty())
	}

	@Test
	fun `default heartbeat file content parses correctly`() {
		val rules = HeartbeatScheduler.parseHeartbeatRules(HeartbeatScheduler.DEFAULT_HEARTBEAT)
		assertEquals(2, rules.size)
	}

	@Test
	fun `shouldExecute for EveryMinutes when never run`() {
		val config = SchedulerConfig(heartbeatCheckIntervalMinutes = 1)
		val tasks = mutableListOf<HeartbeatTask>()
		val scheduler = HeartbeatScheduler(config, "data") { tasks.add(it) }

		val rule = HeartbeatRule(
			id = "test",
			schedule = HeartbeatSchedule.EveryMinutes(30),
			description = "Test",
			rawLine = ""
		)
		// Never executed → should execute
		assertTrue(scheduler.shouldExecute(rule, LocalDateTime.now()))
	}

	@Test
	fun `shouldExecute for WeekdayAt on weekend returns false`() {
		val config = SchedulerConfig(heartbeatCheckIntervalMinutes = 1)
		val tasks = mutableListOf<HeartbeatTask>()
		val scheduler = HeartbeatScheduler(config, "data") { tasks.add(it) }

		val rule = HeartbeatRule(
			id = "test",
			schedule = HeartbeatSchedule.WeekdayAt(LocalTime.of(9, 0)),
			description = "Test",
			rawLine = ""
		)
		// Find next Saturday
		var dt = LocalDateTime.now()
		while (dt.dayOfWeek != DayOfWeek.SATURDAY) {
			dt = dt.plusDays(1)
		}
		dt = dt.withHour(9).withMinute(0)
		assertFalse(scheduler.shouldExecute(rule, dt))
	}

	@Test
	fun `shouldExecute for WeekendAt on weekday returns false`() {
		val config = SchedulerConfig(heartbeatCheckIntervalMinutes = 1)
		val tasks = mutableListOf<HeartbeatTask>()
		val scheduler = HeartbeatScheduler(config, "data") { tasks.add(it) }

		val rule = HeartbeatRule(
			id = "test",
			schedule = HeartbeatSchedule.WeekendAt(LocalTime.of(10, 0)),
			description = "Test",
			rawLine = ""
		)
		// Find next Monday
		var dt = LocalDateTime.now()
		while (dt.dayOfWeek != DayOfWeek.MONDAY) {
			dt = dt.plusDays(1)
		}
		dt = dt.withHour(10).withMinute(0)
		assertFalse(scheduler.shouldExecute(rule, dt))
	}

	@Test
	fun `heartbeat scheduler creates default file when missing`() {
		val tempDir = createTempDirectory("heartbeat-test").toFile()
		try {
			val config = SchedulerConfig(heartbeatEnabled = true, heartbeatFile = "HEARTBEAT.md")
			val tasks = mutableListOf<HeartbeatTask>()
			val scheduler = HeartbeatScheduler(config, tempDir.absolutePath) { tasks.add(it) }
			scheduler.start()

			val file = File(tempDir, "HEARTBEAT.md")
			assertTrue(file.exists())
			assertTrue(scheduler.getRuleCount() > 0)

			scheduler.stop()
		} finally {
			tempDir.deleteRecursively()
		}
	}

	@Test
	fun `heartbeat scheduler disabled does not start`() {
		val config = SchedulerConfig(heartbeatEnabled = false)
		val tasks = mutableListOf<HeartbeatTask>()
		val scheduler = HeartbeatScheduler(config, "data") { tasks.add(it) }
		scheduler.start()
		assertEquals(0, scheduler.getRuleCount())
		scheduler.stop()
	}

	@Test
	fun `reload clears and re-reads rules`() {
		val tempDir = createTempDirectory("heartbeat-reload").toFile()
		try {
			val heartbeatFile = File(tempDir, "HEARTBEAT.md")
			heartbeatFile.writeText("- every 10m: First rule")

			val config = SchedulerConfig(heartbeatEnabled = true, heartbeatFile = "HEARTBEAT.md")
			val scheduler = HeartbeatScheduler(config, tempDir.absolutePath) { }
			scheduler.start()
			assertEquals(1, scheduler.getRuleCount())

			heartbeatFile.writeText("- every 5m: Rule A\n- hourly: Rule B")
			scheduler.reload()
			assertEquals(2, scheduler.getRuleCount())

			scheduler.stop()
		} finally {
			tempDir.deleteRecursively()
		}
	}
}
