package tech.lenooby09.openklaw.memory

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tech.lenooby09.openklaw.agent.ChatMessage
import tech.lenooby09.openklaw.config.MemoryConfig
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryManagerTest {

	private lateinit var tempDir: File
	private lateinit var manager: MemoryManager

	@BeforeEach
	fun setup() {
		tempDir = File(System.getProperty("java.io.tmpdir"), "openklaw-mgr-test-${System.nanoTime()}")
		tempDir.mkdirs()
		manager = MemoryManager(MemoryConfig(dataDir = tempDir.absolutePath))
	}

	@AfterEach
	fun cleanup() {
		tempDir.deleteRecursively()
	}

	@Test
	fun `initialize creates default memory files`() {
		manager.initialize()
		assertTrue(File(tempDir, "SOUL.md").exists())
		assertTrue(File(tempDir, "MEMORY.md").exists())
	}

	@Test
	fun `buildMemoryContext includes soul and memory`() {
		manager.initialize()
		val context = manager.buildMemoryContext("alice")
		assertContains(context, "IDENTITY & PERSONALITY")
		assertContains(context, "LONG-TERM MEMORY")
	}

	@Test
	fun `buildMemoryContext includes user profile when it exists`() {
		manager.initialize()
		manager.memoryFiles.writeUserProfile("alice", "# Alice\nPrefers Kotlin")
		val context = manager.buildMemoryContext("alice")
		assertContains(context, "USER PROFILE (alice)")
		assertContains(context, "Prefers Kotlin")
	}

	@Test
	fun `buildMemoryContext excludes user profile when it does not exist`() {
		manager.initialize()
		val context = manager.buildMemoryContext("nonexistent")
		assertFalse(context.contains("USER PROFILE (nonexistent)"))
	}

	@Test
	fun `logMessage delegates to conversation logger`() {
		manager.initialize()
		val msg = ChatMessage(id = "m1", role = "user", content = "test message", timestamp = 1000L)
		manager.logMessage("session-1", "alice", msg)

		val dates = manager.conversationLogger.listLogDates()
		assertTrue(dates.isNotEmpty())
	}

	@Test
	fun `distillConversation appends to MEMORY when threshold met`() {
		val config = MemoryConfig(dataDir = tempDir.absolutePath, memoryDistillationThresholdMessages = 3)
		val mgr = MemoryManager(config)
		mgr.initialize()

		val messages = (1..5).map {
			ChatMessage(id = "m$it", role = if (it % 2 == 0) "assistant" else "user", content = "Discussion about Kotlin programming patterns and best practices $it", timestamp = it * 1000L)
		}

		mgr.distillConversation("s1", "alice", messages)

		val memory = mgr.memoryFiles.readMemory()
		assertContains(memory, "Session with alice")
		assertContains(memory, "Topics:")
	}

	@Test
	fun `distillConversation does nothing below threshold`() {
		val config = MemoryConfig(dataDir = tempDir.absolutePath, memoryDistillationThresholdMessages = 100)
		val mgr = MemoryManager(config)
		mgr.initialize()

		val messages = listOf(
			ChatMessage(id = "m1", role = "user", content = "Hello", timestamp = 1000L)
		)

		mgr.distillConversation("s1", "alice", messages)

		val memory = mgr.memoryFiles.readMemory()
		assertFalse(memory.contains("Session with alice"))
	}

	@Test
	fun `distillConversation does nothing when disabled`() {
		val config = MemoryConfig(dataDir = tempDir.absolutePath, memoryDistillationEnabled = false, memoryDistillationThresholdMessages = 1)
		val mgr = MemoryManager(config)
		mgr.initialize()

		val messages = (1..5).map {
			ChatMessage(id = "m$it", role = "user", content = "Test $it", timestamp = it * 1000L)
		}

		mgr.distillConversation("s1", "alice", messages)

		val memory = mgr.memoryFiles.readMemory()
		assertFalse(memory.contains("Session with alice"))
	}

	@Test
	fun `searchRelevantMemory returns empty when disabled`() {
		val config = MemoryConfig(dataDir = tempDir.absolutePath, semanticSearchEnabled = false)
		val mgr = MemoryManager(config)
		assertEquals("", mgr.searchRelevantMemory("test"))
	}

	@Test
	fun `searchRelevantMemory returns empty when no logs`() {
		val result = manager.searchRelevantMemory("test")
		assertEquals("", result)
	}

	@Test
	fun `disabled memory manager returns empty context`() {
		val config = MemoryConfig(dataDir = tempDir.absolutePath, enabled = false)
		val mgr = MemoryManager(config)
		mgr.initialize()
		assertEquals("", mgr.buildMemoryContext("alice"))
	}

	@Test
	fun `extractTopics returns top terms from messages`() {
		val topics = manager.extractTopics(listOf(
			"I want to learn about Kotlin coroutines and concurrency",
			"Kotlin coroutines are great for async programming",
			"Can you show me Kotlin coroutine examples?"
		))
		assertContains(topics, "kotlin")
		assertContains(topics, "coroutines")
	}
}
