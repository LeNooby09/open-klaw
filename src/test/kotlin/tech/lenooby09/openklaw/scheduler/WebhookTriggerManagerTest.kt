package tech.lenooby09.openklaw.scheduler

import tech.lenooby09.openklaw.config.SchedulerConfig
import kotlin.test.*

class WebhookTriggerManagerTest {

	@Test
	fun `addTrigger succeeds with valid id`() {
		val config = SchedulerConfig(webhookTriggersEnabled = true)
		val manager = WebhookTriggerManager(config) { }

		val trigger = WebhookTrigger("my-trigger", "Test Trigger", "Do something")
		assertTrue(manager.addTrigger(trigger))
		assertEquals(1, manager.getTriggerCount())
	}

	@Test
	fun `addTrigger rejects invalid id`() {
		val config = SchedulerConfig(webhookTriggersEnabled = true)
		val manager = WebhookTriggerManager(config) { }

		assertFalse(manager.addTrigger(WebhookTrigger("bad id!", "Test", "task")))
		assertFalse(manager.addTrigger(WebhookTrigger("", "Test", "task")))
		assertFalse(manager.addTrigger(WebhookTrigger("a".repeat(65), "Test", "task")))
		assertEquals(0, manager.getTriggerCount())
	}

	@Test
	fun `addTrigger respects max limit`() {
		val config = SchedulerConfig(webhookTriggersEnabled = true, maxWebhookTriggers = 2)
		val manager = WebhookTriggerManager(config) { }

		assertTrue(manager.addTrigger(WebhookTrigger("t1", "T1", "task")))
		assertTrue(manager.addTrigger(WebhookTrigger("t2", "T2", "task")))
		assertFalse(manager.addTrigger(WebhookTrigger("t3", "T3", "task")))
	}

	@Test
	fun `addTrigger fails when disabled`() {
		val config = SchedulerConfig(webhookTriggersEnabled = false)
		val manager = WebhookTriggerManager(config) { }

		assertFalse(manager.addTrigger(WebhookTrigger("t1", "T1", "task")))
	}

	@Test
	fun `removeTrigger returns true when exists`() {
		val config = SchedulerConfig(webhookTriggersEnabled = true)
		val manager = WebhookTriggerManager(config) { }

		manager.addTrigger(WebhookTrigger("t1", "T1", "task"))
		assertTrue(manager.removeTrigger("t1"))
		assertFalse(manager.removeTrigger("t1"))
		assertEquals(0, manager.getTriggerCount())
	}

	@Test
	fun `getTrigger retrieves by id`() {
		val config = SchedulerConfig(webhookTriggersEnabled = true)
		val manager = WebhookTriggerManager(config) { }

		manager.addTrigger(WebhookTrigger("t1", "My Trigger", "my task"))
		val trigger = manager.getTrigger("t1")
		assertNotNull(trigger)
		assertEquals("My Trigger", trigger.name)
		assertNull(manager.getTrigger("nonexistent"))
	}

	@Test
	fun `listTriggers returns all`() {
		val config = SchedulerConfig(webhookTriggersEnabled = true)
		val manager = WebhookTriggerManager(config) { }

		manager.addTrigger(WebhookTrigger("t1", "T1", "task 1"))
		manager.addTrigger(WebhookTrigger("t2", "T2", "task 2"))

		assertEquals(2, manager.listTriggers().size)
	}

	@Test
	fun `verifySignature with valid HMAC`() {
		val secret = "mysecret"
		val payload = """{"event":"push"}"""

		// Compute expected signature
		val mac = javax.crypto.Mac.getInstance("HmacSHA256")
		mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
		val expected = mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }

		assertTrue(WebhookTriggerManager.verifySignature(payload, "sha256=$expected", secret))
		assertTrue(WebhookTriggerManager.verifySignature(payload, expected, secret))
	}

	@Test
	fun `verifySignature rejects invalid signature`() {
		assertFalse(WebhookTriggerManager.verifySignature("payload", "badsig", "secret"))
		assertFalse(WebhookTriggerManager.verifySignature("payload", "", "secret"))
		assertFalse(WebhookTriggerManager.verifySignature("payload", "   ", "secret"))
	}

	@Test
	fun `verifySignature rejects wrong payload`() {
		val secret = "mysecret"
		val mac = javax.crypto.Mac.getInstance("HmacSHA256")
		mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
		val sig = mac.doFinal("original".toByteArray()).joinToString("") { "%02x".format(it) }

		assertFalse(WebhookTriggerManager.verifySignature("tampered", sig, secret))
	}
}
