package tech.lenooby09.openklaw.messaging

import tech.lenooby09.openklaw.config.*
import kotlin.test.*

class MessagingConfigTest {

	@Test
	fun `default messaging config has all channels disabled`() {
		val config = MessagingConfig()
		assertFalse(config.discord.enabled)
		assertFalse(config.telegram.enabled)
		assertFalse(config.whatsapp.enabled)
		assertFalse(config.slack.enabled)
		assertFalse(config.email.enabled)
		assertTrue(config.webChatEnabled)
	}

	@Test
	fun `discord config resolves empty token when env not set`() {
		val config = DiscordConfig(enabled = true, botTokenEnv = "NONEXISTENT_DISCORD_TOKEN_XYZ")
		assertEquals("", config.botToken)
	}

	@Test
	fun `telegram config resolves empty token when env not set`() {
		val config = TelegramConfig(enabled = true, botTokenEnv = "NONEXISTENT_TELEGRAM_TOKEN_XYZ")
		assertEquals("", config.botToken)
	}

	@Test
	fun `slack config resolves empty token when env not set`() {
		val config = SlackConfig(enabled = true, botTokenEnv = "NONEXISTENT_SLACK_TOKEN_XYZ")
		assertEquals("", config.botToken)
		assertEquals("", config.signingSecret)
	}

	@Test
	fun `whatsapp config resolves empty token when env not set`() {
		val config = WhatsAppConfig(enabled = true, accessTokenEnv = "NONEXISTENT_WHATSAPP_TOKEN_XYZ")
		assertEquals("", config.accessToken)
	}

	@Test
	fun `email config resolves empty password when env not set`() {
		val config = EmailConfig(enabled = true, passwordEnv = "NONEXISTENT_EMAIL_PASS_XYZ")
		assertEquals("", config.password)
	}

	@Test
	fun `email config has sensible gmail defaults`() {
		val config = EmailConfig()
		assertEquals("imap.gmail.com", config.imapHost)
		assertEquals(993, config.imapPort)
		assertTrue(config.imapSsl)
		assertEquals("smtp.gmail.com", config.smtpHost)
		assertEquals(587, config.smtpPort)
		assertTrue(config.smtpStartTls)
	}

	@Test
	fun `discord config default poll interval is reasonable`() {
		val config = DiscordConfig()
		assertEquals(2000, config.pollIntervalMs)
		assertFalse(config.respondToAll)
	}

	@Test
	fun `telegram config default long poll timeout`() {
		val config = TelegramConfig()
		assertEquals(30, config.longPollTimeoutSeconds)
		assertFalse(config.respondToAllGroupMessages)
	}

	@Test
	fun `app config includes messaging config`() {
		val config = AppConfig()
		assertNotNull(config.messaging)
		assertTrue(config.messaging.webChatEnabled)
	}

	@Test
	fun `channel type enum has all expected values`() {
		val types = ChannelType.entries
		assertEquals(6, types.size)
		assertTrue(types.contains(ChannelType.WEBCHAT))
		assertTrue(types.contains(ChannelType.DISCORD))
		assertTrue(types.contains(ChannelType.TELEGRAM))
		assertTrue(types.contains(ChannelType.WHATSAPP))
		assertTrue(types.contains(ChannelType.SLACK))
		assertTrue(types.contains(ChannelType.EMAIL))
	}
}
