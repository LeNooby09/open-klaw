package tech.lenooby09.openklaw.health

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HealthCheckManagerTest {

	private fun simpleCheck(name: String, status: HealthStatus, message: String) = object : HealthCheck {
		override val name = name
		override suspend fun execute() = HealthCheckResult(name, status, message)
	}

	@Test
	fun `all healthy returns HEALTHY`() = runBlocking {
		val mgr = HealthCheckManager()
		mgr.register(simpleCheck("a", HealthStatus.HEALTHY, "ok"))
		mgr.register(simpleCheck("b", HealthStatus.HEALTHY, "ok"))

		val report = mgr.runAll()
		assertEquals(HealthStatus.HEALTHY, report.status)
		assertEquals(2, report.checks.size)
	}

	@Test
	fun `one degraded returns DEGRADED`() = runBlocking {
		val mgr = HealthCheckManager()
		mgr.register(simpleCheck("a", HealthStatus.HEALTHY, "ok"))
		mgr.register(simpleCheck("b", HealthStatus.DEGRADED, "slow"))

		val report = mgr.runAll()
		assertEquals(HealthStatus.DEGRADED, report.status)
	}

	@Test
	fun `one unhealthy returns UNHEALTHY`() = runBlocking {
		val mgr = HealthCheckManager()
		mgr.register(simpleCheck("a", HealthStatus.HEALTHY, "ok"))
		mgr.register(simpleCheck("b", HealthStatus.UNHEALTHY, "down"))

		val report = mgr.runAll()
		assertEquals(HealthStatus.UNHEALTHY, report.status)
	}

	@Test
	fun `exception in check results in UNHEALTHY`() = runBlocking {
		val mgr = HealthCheckManager()
		mgr.register(object : HealthCheck {
			override val name = "failing"
			override suspend fun execute(): HealthCheckResult = throw RuntimeException("boom")
		})

		val report = mgr.runAll()
		assertEquals(HealthStatus.UNHEALTHY, report.status)
		assertTrue(report.checks[0].message.contains("boom"))
	}

	@Test
	fun `runCheck returns specific check`() = runBlocking {
		val mgr = HealthCheckManager()
		mgr.register(simpleCheck("a", HealthStatus.HEALTHY, "ok"))
		mgr.register(simpleCheck("b", HealthStatus.DEGRADED, "slow"))

		val result = mgr.runCheck("b")
		assertNotNull(result)
		assertEquals("b", result!!.name)
		assertEquals(HealthStatus.DEGRADED, result.status)
	}

	@Test
	fun `runCheck returns null for unknown name`() = runBlocking {
		val mgr = HealthCheckManager()
		assertNull(mgr.runCheck("nonexistent"))
	}

	@Test
	fun `diagnostics generates issues for non-healthy`() = runBlocking {
		val mgr = HealthCheckManager()
		mgr.register(simpleCheck("a", HealthStatus.HEALTHY, "ok"))
		mgr.register(simpleCheck("b", HealthStatus.DEGRADED, "slow"))
		mgr.register(simpleCheck("c", HealthStatus.UNHEALTHY, "down"))

		val diag = mgr.runDiagnostics()
		assertEquals(HealthStatus.UNHEALTHY, diag.overallStatus)
		assertEquals(2, diag.issues.size)
		assertEquals(1, diag.healthyComponents)
		assertEquals(3, diag.totalComponents)

		val errorIssue = diag.issues.find { it.severity == "ERROR" }
		assertNotNull(errorIssue)
		assertEquals("c", errorIssue!!.component)

		val warnIssue = diag.issues.find { it.severity == "WARNING" }
		assertNotNull(warnIssue)
		assertEquals("b", warnIssue!!.component)
	}

	@Test
	fun `getCheckCount tracks registered checks`() {
		val mgr = HealthCheckManager()
		assertEquals(0, mgr.getCheckCount())
		mgr.register(simpleCheck("a", HealthStatus.HEALTHY, "ok"))
		assertEquals(1, mgr.getCheckCount())
	}

	@Test
	fun `SystemResourceHealthCheck runs without error`() = runBlocking {
		val check = BuiltInHealthChecks.SystemResourceHealthCheck()
		val result = check.execute()
		assertEquals("system_resources", result.name)
		assertNotNull(result.details["maxMemoryMb"])
		assertNotNull(result.details["availableProcessors"])
	}

	@Test
	fun `ToolRegistryHealthCheck reports no tools as degraded`() = runBlocking {
		val check = BuiltInHealthChecks.ToolRegistryHealthCheck(
			toolCount = { 0 },
			enabledCount = { 0 }
		)
		val result = check.execute()
		assertEquals(HealthStatus.DEGRADED, result.status)
	}

	@Test
	fun `ToolRegistryHealthCheck reports all enabled as healthy`() = runBlocking {
		val check = BuiltInHealthChecks.ToolRegistryHealthCheck(
			toolCount = { 5 },
			enabledCount = { 5 }
		)
		val result = check.execute()
		assertEquals(HealthStatus.HEALTHY, result.status)
	}

	@Test
	fun `LlmHealthCheck reports no providers as unhealthy`() = runBlocking {
		val check = BuiltInHealthChecks.LlmHealthCheck(
			providerCount = { 0 },
			healthCheck = { emptyMap() }
		)
		val result = check.execute()
		assertEquals(HealthStatus.UNHEALTHY, result.status)
	}

	@Test
	fun `ChannelHealthCheck with no channels is healthy`() = runBlocking {
		val check = BuiltInHealthChecks.ChannelHealthCheck(
			channelCount = { 0 },
			channelStatuses = { emptyMap() }
		)
		val result = check.execute()
		assertEquals(HealthStatus.HEALTHY, result.status)
	}
}
