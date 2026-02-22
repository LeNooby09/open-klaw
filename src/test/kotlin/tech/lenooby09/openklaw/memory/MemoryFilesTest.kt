package tech.lenooby09.openklaw.memory

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tech.lenooby09.openklaw.config.MemoryConfig
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryFilesTest {

	private lateinit var tempDir: File
	private lateinit var memoryFiles: MemoryFiles

	@BeforeEach
	fun setup() {
		tempDir = File(System.getProperty("java.io.tmpdir"), "openklaw-mem-test-${System.nanoTime()}")
		tempDir.mkdirs()
		memoryFiles = MemoryFiles(MemoryConfig(dataDir = tempDir.absolutePath))
	}

	@AfterEach
	fun cleanup() {
		tempDir.deleteRecursively()
	}

	@Test
	fun `initialize creates default SOUL and MEMORY files`() {
		memoryFiles.initialize()

		assertTrue(File(tempDir, "SOUL.md").exists())
		assertTrue(File(tempDir, "MEMORY.md").exists())
	}

	@Test
	fun `readSoul returns default when file does not exist`() {
		val soul = memoryFiles.readSoul()
		assertContains(soul, "Open-Klaw")
		assertContains(soul, "Personality")
	}

	@Test
	fun `writeSoul and readSoul round-trip`() {
		val content = "# Custom Soul\nI am a test agent."
		assertTrue(memoryFiles.writeSoul(content))
		assertEquals(content, memoryFiles.readSoul())
	}

	@Test
	fun `readMemory returns default when file does not exist`() {
		val memory = memoryFiles.readMemory()
		assertContains(memory, "MEMORY")
		assertContains(memory, "Key Facts")
	}

	@Test
	fun `writeMemory and readMemory round-trip`() {
		val content = "# My Memory\n- Fact 1\n- Fact 2"
		assertTrue(memoryFiles.writeMemory(content))
		assertEquals(content, memoryFiles.readMemory())
	}

	@Test
	fun `appendMemory adds entry to MEMORY file`() {
		memoryFiles.initialize()
		assertTrue(memoryFiles.appendMemory("- New fact discovered"))
		val memory = memoryFiles.readMemory()
		assertContains(memory, "New fact discovered")
	}

	@Test
	fun `appendMemory respects max file size`() {
		val bigConfig = MemoryConfig(dataDir = tempDir.absolutePath, maxMemoryFileSize = 50)
		val bigMemFiles = MemoryFiles(bigConfig)
		bigMemFiles.initialize()

		// Write enough to exceed limit
		File(tempDir, "MEMORY.md").writeText("x".repeat(100))
		assertFalse(bigMemFiles.appendMemory("- Should not be appended"))
	}

	@Test
	fun `readUserProfile returns default for unknown user`() {
		val profile = memoryFiles.readUserProfile("testuser")
		assertContains(profile, "testuser")
		assertContains(profile, "Preferences")
	}

	@Test
	fun `writeUserProfile and readUserProfile round-trip`() {
		val content = "# User Profile\nPrefers dark mode"
		assertTrue(memoryFiles.writeUserProfile("alice", content))
		assertEquals(content, memoryFiles.readUserProfile("alice"))
	}

	@Test
	fun `appendUserProfile adds entry to user file`() {
		memoryFiles.writeUserProfile("bob", "# Bob\n")
		assertTrue(memoryFiles.appendUserProfile("bob", "- Likes Kotlin"))
		val profile = memoryFiles.readUserProfile("bob")
		assertContains(profile, "Likes Kotlin")
	}

	@Test
	fun `userProfileExists returns false when no file`() {
		assertFalse(memoryFiles.userProfileExists("nobody"))
	}

	@Test
	fun `userProfileExists returns true after write`() {
		memoryFiles.writeUserProfile("alice", "test")
		assertTrue(memoryFiles.userProfileExists("alice"))
	}

	@Test
	fun `disabled config prevents appendMemory`() {
		val disabledFiles = MemoryFiles(MemoryConfig(dataDir = tempDir.absolutePath, enabled = false))
		assertFalse(disabledFiles.appendMemory("- Should not work"))
	}

	@Test
	fun `disabled config prevents appendUserProfile`() {
		val disabledFiles = MemoryFiles(MemoryConfig(dataDir = tempDir.absolutePath, enabled = false))
		assertFalse(disabledFiles.appendUserProfile("alice", "- Should not work"))
	}
}
