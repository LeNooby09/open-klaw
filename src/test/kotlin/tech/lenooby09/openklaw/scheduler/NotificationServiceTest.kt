package tech.lenooby09.openklaw.scheduler

import kotlinx.coroutines.test.runTest
import tech.lenooby09.openklaw.agent.AgentLoop
import tech.lenooby09.openklaw.config.LlmConfig
import tech.lenooby09.openklaw.config.SchedulerConfig
import tech.lenooby09.openklaw.llm.LlmOrchestrator
import tech.lenooby09.openklaw.messaging.*
import tech.lenooby09.openklaw.session.SessionManager
import kotlin.test.*

class NotificationServiceTest {

	private fun createService(
		notificationsEnabled: Boolean = true,
		defaultChannel: String = "DISCORD"
	): Triple<NotificationService, SessionManager, ChannelRouter> {
		val orchestrator = LlmOrchestrator(LlmConfig())
		val agentLoop = AgentLoop(orchestrator)
		val sessionManager = SessionManager()
		val channelRouter = ChannelRouter(agentLoop, sessionManager)

		val config = SchedulerConfig(
			notificationsEnabled = notificationsEnabled,
			defaultNotificationChannel = defaultChannel
		)
		val service = NotificationService(config, channelRouter, sessionManager)
		return Triple(service, sessionManager, channelRouter)
	}

	@Test
	fun `setUserPreference and getUserPreference`() {
		val (service, _, _) = createService()
		assertNull(service.getUserPreference("alice"))
		service.setUserPreference("alice", ChannelType.TELEGRAM)
		assertEquals(ChannelType.TELEGRAM, service.getUserPreference("alice"))
	}

	@Test
	fun `clearUserPreference removes preference`() {
		val (service, _, _) = createService()
		service.setUserPreference("alice", ChannelType.SLACK)
		service.clearUserPreference("alice")
		assertNull(service.getUserPreference("alice"))
	}

	@Test
	fun `notify fails when notifications disabled`() = runTest {
		val (service, _, _) = createService(notificationsEnabled = false)
		val result = service.notify("alice", "Test", "Hello")
		assertFalse(result)
		assertEquals(0, service.getHistoryCount())
	}

	@Test
	fun `notify records in history even when delivery fails`() = runTest {
		val (service, sessionManager, _) = createService()
		sessionManager.createUser("alice", "pass123")

		val result = service.notify("alice", "Test", "Hello")
		// Will fail because no channel is connected/linked, but should still record
		assertFalse(result)
		assertEquals(1, service.getHistoryCount())
	}

	@Test
	fun `getHistory filters by username`() = runTest {
		val (service, sessionManager, _) = createService()
		sessionManager.createUser("alice", "pass123")
		sessionManager.createUser("bob", "pass456")

		service.notify("alice", "For Alice", "msg1")
		service.notify("bob", "For Bob", "msg2")
		service.notify("alice", "Also Alice", "msg3")

		val aliceHistory = service.getHistory("alice")
		assertEquals(2, aliceHistory.size)

		val allHistory = service.getHistory()
		assertEquals(3, allHistory.size)
	}

	@Test
	fun `getHistory respects limit`() = runTest {
		val (service, sessionManager, _) = createService()
		sessionManager.createUser("alice", "pass123")

		repeat(10) {
			service.notify("alice", "Notification $it", "msg $it")
		}

		val limited = service.getHistory("alice", limit = 3)
		assertEquals(3, limited.size)
	}

	@Test
	fun `getHistory sorted by timestamp descending`() = runTest {
		val (service, sessionManager, _) = createService()
		sessionManager.createUser("alice", "pass123")

		service.notify("alice", "First", "msg1")
		Thread.sleep(10)
		service.notify("alice", "Second", "msg2")

		val history = service.getHistory("alice")
		assertTrue(history[0].timestamp >= history[1].timestamp)
	}

	@Test
	fun `notify with connected channel and linked account succeeds`() = runTest {
		val (service, sessionManager, channelRouter) = createService()
		sessionManager.createUser("alice", "pass123")
		sessionManager.linkChannelAccount("alice", "DISCORD", "alice-discord-id")

		val testChannel = TestNotificationChannel(ChannelType.DISCORD)
		channelRouter.register(testChannel)
		testChannel.simulateConnect()

		service.setUserPreference("alice", ChannelType.DISCORD)
		val result = service.notify("alice", "Build Failed", "Your CI build failed")
		assertTrue(result)
		assertEquals(1, testChannel.sentMessages.size)
		assertTrue(testChannel.sentMessages[0].content.contains("Build Failed"))
	}

	@Test
	fun `broadcast sends to all users`() = runTest {
		val (service, sessionManager, channelRouter) = createService()
		sessionManager.createUser("alice", "pass123")
		sessionManager.createUser("bob", "pass456")

		sessionManager.linkChannelAccount("alice", "DISCORD", "alice-id")
		sessionManager.linkChannelAccount("bob", "DISCORD", "bob-id")

		val testChannel = TestNotificationChannel(ChannelType.DISCORD)
		channelRouter.register(testChannel)
		testChannel.simulateConnect()

		service.setUserPreference("alice", ChannelType.DISCORD)
		service.setUserPreference("bob", ChannelType.DISCORD)

		val count = service.broadcast("System Update", "New version available")
		assertEquals(2, count)
		assertEquals(2, testChannel.sentMessages.size)
	}

	/**
	 * Minimal test channel for notification testing.
	 */
	private class TestNotificationChannel(
		override val channelType: ChannelType,
		override val displayName: String = channelType.name,
	) : MessageChannel {
		override var connected: Boolean = false
			private set

		private var handler: (suspend (InboundMessage) -> Unit)? = null
		val sentMessages = mutableListOf<OutboundMessage>()

		override suspend fun start() { connected = true }
		override suspend fun stop() { connected = false }

		fun simulateConnect() { connected = true }

		override suspend fun sendMessage(message: OutboundMessage): Boolean {
			sentMessages.add(message)
			return true
		}

		override fun onMessage(handler: suspend (InboundMessage) -> Unit) {
			this.handler = handler
		}
	}
}
