package tech.lenooby09.openklaw.messaging

import kotlin.test.*

class MessageSplittingTest {

	@Test
	fun `short message returns single chunk`() {
		val result = DiscordChannel.splitMessage("Hello world", 2000)
		assertEquals(1, result.size)
		assertEquals("Hello world", result[0])
	}

	@Test
	fun `empty message returns single empty chunk`() {
		val result = DiscordChannel.splitMessage("", 2000)
		assertEquals(1, result.size)
		assertEquals("", result[0])
	}

	@Test
	fun `message at exact limit returns single chunk`() {
		val msg = "a".repeat(2000)
		val result = DiscordChannel.splitMessage(msg, 2000)
		assertEquals(1, result.size)
		assertEquals(2000, result[0].length)
	}

	@Test
	fun `long message splits at newline`() {
		val line1 = "a".repeat(1500)
		val line2 = "b".repeat(1500)
		val msg = "$line1\n$line2"
		val result = DiscordChannel.splitMessage(msg, 2000)
		assertEquals(2, result.size)
		assertEquals(line1, result[0])
		assertEquals(line2, result[1])
	}

	@Test
	fun `long message without newline splits at max length`() {
		val msg = "x".repeat(5000)
		val result = DiscordChannel.splitMessage(msg, 2000)
		assertEquals(3, result.size)
		assertEquals(2000, result[0].length)
		assertEquals(2000, result[1].length)
		assertEquals(1000, result[2].length)
	}

	@Test
	fun `telegram split works the same way`() {
		val msg = "y".repeat(8000)
		val result = TelegramChannel.splitMessage(msg, 4096)
		assertEquals(2, result.size)
		assertEquals(4096, result[0].length)
		assertEquals(3904, result[1].length)
	}
}
