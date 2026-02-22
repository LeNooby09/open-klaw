package tech.lenooby09.openklaw.messaging

import kotlinx.coroutines.test.runTest
import tech.lenooby09.openklaw.agent.AgentLoop
import tech.lenooby09.openklaw.llm.LlmOrchestrator
import tech.lenooby09.openklaw.config.LlmConfig
import tech.lenooby09.openklaw.session.SessionManager
import kotlin.test.*

class ChannelRouterTest {

	private fun createRouter(): ChannelRouter {
		val orchestrator = LlmOrchestrator(LlmConfig())
		val agentLoop = AgentLoop(orchestrator)
		val sessionManager = SessionManager()
		return ChannelRouter(agentLoop, sessionManager)
	}

	@Test
	fun `register and unregister channels`() {
		val router = createRouter()
		val channel = TestChannel(ChannelType.DISCORD)

		router.register(channel)
		assertEquals(1, router.getChannelCount())
		assertNotNull(router.getChannel(ChannelType.DISCORD))

		router.unregister(ChannelType.DISCORD)
		assertEquals(0, router.getChannelCount())
		assertNull(router.getChannel(ChannelType.DISCORD))
	}

	@Test
	fun `getRegisteredChannels returns all channels`() {
		val router = createRouter()
		val discord = TestChannel(ChannelType.DISCORD)
		val telegram = TestChannel(ChannelType.TELEGRAM)

		router.register(discord)
		router.register(telegram)

		val channels = router.getRegisteredChannels()
		assertEquals(2, channels.size)
	}

	@Test
	fun `startAll and stopAll lifecycle`() = runTest {
		val router = createRouter()
		val channel = TestChannel(ChannelType.WEBCHAT)
		router.register(channel)

		router.startAll()
		assertTrue(channel.connected)

		router.stopAll()
		assertFalse(channel.connected)
	}

	@Test
	fun `clearSession removes session mapping`() {
		val router = createRouter()
		assertEquals(0, router.getSessionCount())
		router.clearSession(ChannelType.DISCORD, "user123")
		assertEquals(0, router.getSessionCount())
	}

	@Test
	fun `registering same channel type replaces previous`() {
		val router = createRouter()
		val first = TestChannel(ChannelType.SLACK)
		val second = TestChannel(ChannelType.SLACK)

		router.register(first)
		router.register(second)

		assertEquals(1, router.getChannelCount())
		assertSame(second, router.getChannel(ChannelType.SLACK))
	}

	/**
	 * Minimal test channel implementation for unit testing.
	 */
	private class TestChannel(
		override val channelType: ChannelType,
		override val displayName: String = channelType.name,
	) : MessageChannel {
		override var connected: Boolean = false
			private set

		private var handler: (suspend (InboundMessage) -> Unit)? = null
		val sentMessages = mutableListOf<OutboundMessage>()

		override suspend fun start() { connected = true }
		override suspend fun stop() { connected = false }

		override suspend fun sendMessage(message: OutboundMessage): Boolean {
			sentMessages.add(message)
			return true
		}

		override fun onMessage(handler: suspend (InboundMessage) -> Unit) {
			this.handler = handler
		}

		suspend fun simulateInbound(message: InboundMessage) {
			handler?.invoke(message)
		}
	}
}
