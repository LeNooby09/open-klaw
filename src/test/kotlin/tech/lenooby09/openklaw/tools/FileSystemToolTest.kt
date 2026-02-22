package tech.lenooby09.openklaw.tools

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tech.lenooby09.openklaw.config.ToolsConfig
import java.io.File

class FileSystemToolTest {
	private lateinit var tempDir: File
	private lateinit var tool: FileSystemTool

	@BeforeEach
	fun setup() {
		tempDir = File(System.getProperty("java.io.tmpdir"), "openklaw_test_${System.nanoTime()}")
		tempDir.mkdirs()
		val config = ToolsConfig(fileSystemEnabled = true, fileSystemBaseDir = tempDir.absolutePath)
		tool = FileSystemTool(config)
	}

	@AfterEach
	fun cleanup() {
		tempDir.deleteRecursively()
	}

	@Test
	fun `write and read file`() = runBlocking {
		val writeResult = tool.execute(mapOf("operation" to "write", "path" to "test.txt", "content" to "Hello World"))
		assertTrue(writeResult.success)

		val readResult = tool.execute(mapOf("operation" to "read", "path" to "test.txt"))
		assertTrue(readResult.success)
		assertEquals("Hello World", readResult.output)
	}

	@Test
	fun `append to file`() = runBlocking {
		tool.execute(mapOf("operation" to "write", "path" to "append.txt", "content" to "line1\n"))
		tool.execute(mapOf("operation" to "append", "path" to "append.txt", "content" to "line2\n"))

		val readResult = tool.execute(mapOf("operation" to "read", "path" to "append.txt"))
		assertTrue(readResult.success)
		assertTrue(readResult.output.contains("line1"))
		assertTrue(readResult.output.contains("line2"))
	}

	@Test
	fun `list directory`() = runBlocking {
		File(tempDir, "file1.txt").writeText("a")
		File(tempDir, "file2.txt").writeText("b")
		File(tempDir, "subdir").mkdirs()

		val result = tool.execute(mapOf("operation" to "list", "path" to "."))
		assertTrue(result.success)
		assertTrue(result.output.contains("file1.txt"))
		assertTrue(result.output.contains("file2.txt"))
		assertTrue(result.output.contains("[DIR]"))
	}

	@Test
	fun `search files`() = runBlocking {
		File(tempDir, "readme.md").writeText("hello")
		File(tempDir, "code.kt").writeText("fun main()")

		val result = tool.execute(mapOf("operation" to "search", "path" to ".", "pattern" to "*.md", "recursive" to "false"))
		assertTrue(result.success)
		assertTrue(result.output.contains("readme.md"))
	}

	@Test
	fun `delete file`() = runBlocking {
		File(tempDir, "to_delete.txt").writeText("bye")
		val result = tool.execute(mapOf("operation" to "delete", "path" to "to_delete.txt"))
		assertTrue(result.success)
		assertFalse(File(tempDir, "to_delete.txt").exists())
	}

	@Test
	fun `mkdir creates directory`() = runBlocking {
		val result = tool.execute(mapOf("operation" to "mkdir", "path" to "newdir"))
		assertTrue(result.success)
		assertTrue(File(tempDir, "newdir").isDirectory)
	}

	@Test
	fun `exists check`() = runBlocking {
		File(tempDir, "exists.txt").writeText("hi")
		val result = tool.execute(mapOf("operation" to "exists", "path" to "exists.txt"))
		assertTrue(result.success)
		assertTrue(result.metadata["exists"] == "true")
	}

	@Test
	fun `file info`() = runBlocking {
		File(tempDir, "info.txt").writeText("content")
		val result = tool.execute(mapOf("operation" to "info", "path" to "info.txt"))
		assertTrue(result.success)
		assertTrue(result.output.contains("info.txt"))
		assertTrue(result.output.contains("Size:"))
	}

	@Test
	fun `read non-existent file`() = runBlocking {
		val result = tool.execute(mapOf("operation" to "read", "path" to "nope.txt"))
		assertFalse(result.success)
		assertTrue(result.error!!.contains("not found"))
	}

	@Test
	fun `path traversal blocked`() = runBlocking {
		val result = tool.execute(mapOf("operation" to "read", "path" to "../../../etc/passwd"))
		assertFalse(result.success)
		assertTrue(result.error!!.contains("outside"))
	}

	@Test
	fun `unknown operation`() = runBlocking {
		val result = tool.execute(mapOf("operation" to "hack", "path" to "."))
		assertFalse(result.success)
		assertTrue(result.error!!.contains("Unknown operation"))
	}

	@Test
	fun `tool name is file`() {
		assertEquals("file", tool.name)
	}
}
