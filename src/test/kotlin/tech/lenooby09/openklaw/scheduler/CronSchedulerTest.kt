package tech.lenooby09.openklaw.scheduler

import tech.lenooby09.openklaw.config.SchedulerConfig
import java.time.LocalDateTime
import kotlin.test.*

class CronSchedulerTest {

	@Test
	fun `CronExpression parse valid 5-part expression`() {
		val expr = CronExpression.parse("0 9 * * 1")
		assertNotNull(expr)
	}

	@Test
	fun `CronExpression parse invalid expression returns null`() {
		assertNull(CronExpression.parse("bad"))
		assertNull(CronExpression.parse("1 2 3"))
		assertNull(CronExpression.parse(""))
	}

	@Test
	fun `CronExpression isValid`() {
		assertTrue(CronExpression.isValid("* * * * *"))
		assertTrue(CronExpression.isValid("0 9 * * *"))
		assertTrue(CronExpression.isValid("0/15 * * * *"))
		assertTrue(CronExpression.isValid("0 9,17 * * 1,2,3,4,5"))
		assertFalse(CronExpression.isValid("bad"))
		assertFalse(CronExpression.isValid(""))
	}

	@Test
	fun `CronField Any matches everything`() {
		val field = CronField.parse("*", 0, 59)
		assertTrue(field is CronField.Any)
		assertTrue(field.matches(0))
		assertTrue(field.matches(59))
	}

	@Test
	fun `CronField Exact matches only specific value`() {
		val field = CronField.parse("30", 0, 59)
		assertTrue(field is CronField.Exact)
		assertTrue(field.matches(30))
		assertFalse(field.matches(29))
	}

	@Test
	fun `CronField Step matches at intervals`() {
		val field = CronField.parse("0/15", 0, 59)
		assertTrue(field is CronField.Step)
		assertTrue(field.matches(0))
		assertTrue(field.matches(15))
		assertTrue(field.matches(30))
		assertTrue(field.matches(45))
		assertFalse(field.matches(10))
	}

	@Test
	fun `CronField Step with wildcard start`() {
		val field = CronField.parse("*/10", 0, 59)
		assertTrue(field is CronField.Step)
		assertTrue(field.matches(0))
		assertTrue(field.matches(10))
		assertTrue(field.matches(20))
		assertFalse(field.matches(5))
	}

	@Test
	fun `CronField List matches any value in set`() {
		val field = CronField.parse("1,3,5", 0, 59)
		assertTrue(field is CronField.List)
		assertTrue(field.matches(1))
		assertTrue(field.matches(3))
		assertTrue(field.matches(5))
		assertFalse(field.matches(2))
		assertFalse(field.matches(4))
	}

	@Test
	fun `CronExpression matches specific datetime`() {
		// Every day at 09:30
		val expr = CronExpression.parse("30 9 * * *")!!
		val matching = LocalDateTime.of(2026, 2, 22, 9, 30)
		val nonMatching = LocalDateTime.of(2026, 2, 22, 9, 31)
		assertTrue(expr.matches(matching))
		assertFalse(expr.matches(nonMatching))
	}

	@Test
	fun `CronExpression matches day of week`() {
		// Monday at 09:00 (Monday = 1 in java.time, but 1 % 7 = 1 in our cron)
		val expr = CronExpression.parse("0 9 * * 1")!!
		// 2026-02-23 is Monday
		val monday = LocalDateTime.of(2026, 2, 23, 9, 0)
		val tuesday = LocalDateTime.of(2026, 2, 24, 9, 0)
		assertTrue(expr.matches(monday))
		assertFalse(expr.matches(tuesday))
	}

	@Test
	fun `addJob respects max limit`() {
		val config = SchedulerConfig(cronEnabled = true, maxCronJobs = 2)
		val scheduler = CronScheduler(config) { }

		assertTrue(scheduler.addJob(CronJob("j1", "Job 1", "* * * * *", "task")))
		assertTrue(scheduler.addJob(CronJob("j2", "Job 2", "* * * * *", "task")))
		assertFalse(scheduler.addJob(CronJob("j3", "Job 3", "* * * * *", "task")))
		assertEquals(2, scheduler.getJobCount())
	}

	@Test
	fun `addJob rejects invalid expression`() {
		val config = SchedulerConfig(cronEnabled = true)
		val scheduler = CronScheduler(config) { }

		assertFalse(scheduler.addJob(CronJob("j1", "Job 1", "invalid", "task")))
		assertEquals(0, scheduler.getJobCount())
	}

	@Test
	fun `removeJob returns true when exists`() {
		val config = SchedulerConfig(cronEnabled = true)
		val scheduler = CronScheduler(config) { }

		scheduler.addJob(CronJob("j1", "Job 1", "* * * * *", "task"))
		assertTrue(scheduler.removeJob("j1"))
		assertFalse(scheduler.removeJob("j1"))
		assertEquals(0, scheduler.getJobCount())
	}

	@Test
	fun `listJobs returns all added jobs`() {
		val config = SchedulerConfig(cronEnabled = true)
		val scheduler = CronScheduler(config) { }

		scheduler.addJob(CronJob("j1", "Job 1", "0 9 * * *", "task 1"))
		scheduler.addJob(CronJob("j2", "Job 2", "0 17 * * *", "task 2"))

		val jobs = scheduler.listJobs()
		assertEquals(2, jobs.size)
	}

	@Test
	fun `getJob retrieves by id`() {
		val config = SchedulerConfig(cronEnabled = true)
		val scheduler = CronScheduler(config) { }

		scheduler.addJob(CronJob("j1", "Job 1", "0 9 * * *", "my task"))
		val job = scheduler.getJob("j1")
		assertNotNull(job)
		assertEquals("Job 1", job.name)
		assertNull(scheduler.getJob("nonexistent"))
	}

	@Test
	fun `disabled scheduler does not start`() {
		val config = SchedulerConfig(cronEnabled = false)
		val scheduler = CronScheduler(config) { }
		scheduler.start()
		scheduler.stop()
	}
}
