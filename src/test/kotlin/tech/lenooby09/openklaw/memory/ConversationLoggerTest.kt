package tech.lenooby09.openklaw.memory

import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tech.lenooby09.openklaw.agent.ChatMessage
import tech.lenooby09.openklaw.config.MemoryConfig
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationLoggerTest {

	private val sessionId1 = "00000000-0000-0000-0000-000000000001"
	private val sessionId2 = "00000000-0000-0000-0000-000000000002"

	private lateinit var tempDir: File
	private lateinit var logger: ConversationLogger

	@BeforeEach
	fun setup() {
		tempDir = File(System.getProperty("java.io.tmpdir"), "openklaw-test-${System.nanoTime()}")
		tempDir.mkdirs()
		logger = ConversationLogger(MemoryConfig(dataDir = tempDir.absolutePath))
	}

	@AfterEach
	fun cleanup() {
		tempDir.deleteRecursively()
	}

	@Test
	fun `logMessage creates JSONL file with correct content`() {
		val message = ChatMessage(id = "msg-1", role = "user", content = "Hello agent", timestamp = 1000L)
		logger.logMessage(sessionId1, "alice", message)

		val dates = logger.listLogDates()
		assertTrue(dates.isNotEmpty(), "Should have at least one date directory")

		val sessions = logger.listSessionLogs(dates.first())
		assertTrue(sessions.contains(sessionId1), "Should contain $sessionId1")

		val entries = logger.readSessionLog(dates.first(), sessionId1)
		assertEquals(1, entries.size)
		assertEquals("user", entries[0]["role"]?.jsonPrimitive?.content)
		assertEquals("Hello agent", entries[0]["content"]?.jsonPrimitive?.content)
		assertEquals("alice", entries[0]["username"]?.jsonPrimitive?.content)
	}

	@Test
	fun `logMessage appends multiple messages to same session`() {
		val msg1 = ChatMessage(id = "msg-1", role = "user", content = "Hello", timestamp = 1000L)
		val msg2 = ChatMessage(id = "msg-2", role = "assistant", content = "Hi there!", timestamp = 2000L, model = "gpt-4", provider = "openai")
		logger.logMessage(sessionId1, "bob", msg1)
		logger.logMessage(sessionId1, "bob", msg2)

		val dates = logger.listLogDates()
		val entries = logger.readSessionLog(dates.first(), sessionId1)
		assertEquals(2, entries.size)
		assertEquals("Hello", entries[0]["content"]?.jsonPrimitive?.content)
		assertEquals("Hi there!", entries[1]["content"]?.jsonPrimitive?.content)
		assertEquals("gpt-4", entries[1]["model"]?.jsonPrimitive?.content)
	}

	@Test
	fun `logMessage does nothing when logging disabled`() {
		val disabledLogger = ConversationLogger(MemoryConfig(dataDir = tempDir.absolutePath, conversationLoggingEnabled = false))
		val message = ChatMessage(id = "msg-1", role = "user", content = "Hello")
		disabledLogger.logMessage(sessionId1, "alice", message)

		assertTrue(logger.listLogDates().isEmpty(), "No logs should be created when disabled")
	}

	@Test
	fun `readAllLogs returns entries across dates and sessions`() {
		val msg1 = ChatMessage(id = "msg-1", role = "user", content = "First message", timestamp = 1000L)
		val msg2 = ChatMessage(id = "msg-2", role = "assistant", content = "Second message", timestamp = 2000L)
		logger.logMessage(sessionId1, "alice", msg1)
		logger.logMessage(sessionId2, "bob", msg2)

		val allLogs = logger.readAllLogs()
		assertEquals(2, allLogs.size)
	}

	@Test
	fun `readDailyLogs returns empty for nonexistent date`() {
		val entries = logger.readDailyLogs("2099-01-01")
		assertTrue(entries.isEmpty())
	}

	@Test
	fun `readSessionLog returns empty for nonexistent session`() {
		val entries = logger.readSessionLog("2099-01-01", "00000000-0000-0000-0000-000000000099")
		assertTrue(entries.isEmpty())
	}
}
