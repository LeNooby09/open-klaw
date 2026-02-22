package tech.lenooby09.openklaw.memory

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tech.lenooby09.openklaw.agent.ChatMessage
import tech.lenooby09.openklaw.config.MemoryConfig
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticMemorySearchTest {

	private lateinit var tempDir: File
	private lateinit var config: MemoryConfig
	private lateinit var logger: ConversationLogger
	private lateinit var search: SemanticMemorySearch

	@BeforeEach
	fun setup() {
		tempDir = File(System.getProperty("java.io.tmpdir"), "openklaw-search-test-${System.nanoTime()}")
		tempDir.mkdirs()
		config = MemoryConfig(dataDir = tempDir.absolutePath)
		logger = ConversationLogger(config)
		search = SemanticMemorySearch(config, logger)
	}

	@AfterEach
	fun cleanup() {
		tempDir.deleteRecursively()
	}

	@Test
	fun `search returns empty when no logs exist`() {
		val results = search.search("hello")
		assertTrue(results.isEmpty())
	}

	@Test
	fun `search returns relevant results`() {
		// Log some conversations
		logger.logMessage("s1", "alice", ChatMessage(id = "1", role = "user", content = "How do I write Kotlin coroutines?", timestamp = 1000L))
		logger.logMessage("s1", "alice", ChatMessage(id = "2", role = "assistant", content = "Kotlin coroutines use suspend functions and coroutine builders like launch and async.", timestamp = 2000L))
		logger.logMessage("s2", "bob", ChatMessage(id = "3", role = "user", content = "What is the weather today?", timestamp = 3000L))
		logger.logMessage("s2", "bob", ChatMessage(id = "4", role = "assistant", content = "I cannot check the weather, but you can use a weather API.", timestamp = 4000L))

		// Force reindex
		search.reindex()

		// Search for Kotlin-related content
		val results = search.search("Kotlin coroutines suspend")
		assertTrue(results.isNotEmpty(), "Should find relevant results")
		assertTrue(results.first().content.contains("Kotlin") || results.first().content.contains("coroutine"),
			"Top result should be about Kotlin/coroutines")
	}

	@Test
	fun `search respects maxResults`() {
		// Log many messages
		for (i in 1..20) {
			logger.logMessage("s$i", "alice", ChatMessage(id = "m$i", role = "user", content = "Message about programming topic number $i", timestamp = i * 1000L))
		}

		search.reindex()

		val results = search.search("programming topic", maxResults = 3)
		assertTrue(results.size <= 3, "Should return at most 3 results")
	}

	@Test
	fun `search returns empty when disabled`() {
		val disabledConfig = MemoryConfig(dataDir = tempDir.absolutePath, semanticSearchEnabled = false)
		val disabledSearch = SemanticMemorySearch(disabledConfig, logger)

		logger.logMessage("s1", "alice", ChatMessage(id = "1", role = "user", content = "Hello world", timestamp = 1000L))

		val results = disabledSearch.search("Hello")
		assertTrue(results.isEmpty())
	}

	@Test
	fun `tokenize filters stop words and short tokens`() {
		val tokens = search.tokenize("The quick brown fox jumps over a lazy dog")
		assertTrue("the" !in tokens, "Stop words should be filtered")
		assertTrue("a" !in tokens, "Single-char tokens should be filtered")
		assertTrue("quick" in tokens)
		assertTrue("brown" in tokens)
		assertTrue("fox" in tokens)
	}

	@Test
	fun `tokenize handles special characters`() {
		val tokens = search.tokenize("Hello, world! How's it going? test@email.com")
		assertTrue(tokens.all { it.matches(Regex("[a-z0-9]+")) }, "All tokens should be alphanumeric")
	}

	@Test
	fun `reindex handles empty logs gracefully`() {
		search.reindex()
		val results = search.search("anything")
		assertTrue(results.isEmpty())
	}
}
