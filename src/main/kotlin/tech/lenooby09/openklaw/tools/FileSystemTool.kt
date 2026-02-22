package tech.lenooby09.openklaw.tools

import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.ToolsConfig
import java.io.File

/**
 * Provides file system access: read, write, list, search, delete, and manage files/directories.
 * Operations are scoped to the configured base directory for safety.
 */
class FileSystemTool(private val config: ToolsConfig) : Tool {
	private val logger = LoggerFactory.getLogger(FileSystemTool::class.java)

	override val name = "file"
	override val description = "Read, write, list, search, and manage files and directories."
	override val parameters = listOf(
		ToolParameter("operation", "Operation: read, write, append, list, search, delete, mkdir, exists, info", type = "string", required = true),
		ToolParameter("path", "File or directory path (relative to base directory)", type = "string", required = true),
		ToolParameter("content", "Content to write (for write/append operations)", type = "string", required = false),
		ToolParameter("pattern", "Search pattern (glob or substring for search operation)", type = "string", required = false),
		ToolParameter("recursive", "Whether to recurse into subdirectories (for list/search)", type = "boolean", required = false, defaultValue = "false")
	)
	override val enabled: Boolean get() = config.fileSystemEnabled

	private val baseDir: File get() = File(config.fileSystemBaseDir).canonicalFile

	override suspend fun execute(arguments: Map<String, String>): ToolResult {
		val operation = arguments["operation"] ?: return ToolResult(name, false, "", error = "Missing 'operation' argument")
		val path = arguments["path"] ?: return ToolResult(name, false, "", error = "Missing 'path' argument")

		val targetFile = resolveAndValidatePath(path)
			?: return ToolResult(name, false, "", error = "Path is outside the allowed base directory.")

		return when (operation.lowercase()) {
			"read" -> readFile(targetFile)
			"write" -> writeFile(targetFile, arguments["content"] ?: "")
			"append" -> appendFile(targetFile, arguments["content"] ?: "")
			"list" -> listDirectory(targetFile, arguments["recursive"]?.toBoolean() ?: false)
			"search" -> searchFiles(targetFile, arguments["pattern"] ?: "*", arguments["recursive"]?.toBoolean() ?: false)
			"delete" -> deleteFile(targetFile)
			"mkdir" -> makeDirectory(targetFile)
			"exists" -> checkExists(targetFile)
			"info" -> fileInfo(targetFile)
			else -> ToolResult(name, false, "", error = "Unknown operation: $operation. Use: read, write, append, list, search, delete, mkdir, exists, info")
		}
	}

	private fun resolveAndValidatePath(relativePath: String): File? {
		val resolved = File(baseDir, relativePath).canonicalFile
		return if (resolved.path.startsWith(baseDir.path)) resolved else null
	}

	private fun readFile(file: File): ToolResult {
		if (!file.exists()) return ToolResult(name, false, "", error = "File not found: ${file.name}")
		if (!file.isFile) return ToolResult(name, false, "", error = "Not a file: ${file.name}")
		if (file.length() > config.fileSystemMaxFileSizeBytes) {
			return ToolResult(name, false, "", error = "File too large (${file.length()} bytes, max ${config.fileSystemMaxFileSizeBytes})")
		}

		val content = file.readText()
		return ToolResult(name, true, content, metadata = mapOf("size" to file.length().toString(), "path" to file.path))
	}

	private fun writeFile(file: File, content: String): ToolResult {
		file.parentFile?.mkdirs()
		file.writeText(content)
		logger.info("Wrote file: ${file.path} (${content.length} chars)")
		return ToolResult(name, true, "File written: ${file.name} (${content.length} chars)", metadata = mapOf("path" to file.path))
	}

	private fun appendFile(file: File, content: String): ToolResult {
		file.parentFile?.mkdirs()
		file.appendText(content)
		logger.info("Appended to file: ${file.path} (${content.length} chars)")
		return ToolResult(name, true, "Content appended to: ${file.name} (${content.length} chars)", metadata = mapOf("path" to file.path))
	}

	private fun listDirectory(dir: File, recursive: Boolean): ToolResult {
		if (!dir.exists()) return ToolResult(name, false, "", error = "Directory not found: ${dir.name}")
		if (!dir.isDirectory) return ToolResult(name, false, "", error = "Not a directory: ${dir.name}")

		val entries = if (recursive) {
			dir.walkTopDown().drop(1).take(500).toList()
		} else {
			dir.listFiles()?.toList() ?: emptyList()
		}

		val output = entries.joinToString("\n") { entry ->
			val type = if (entry.isDirectory) "[DIR]" else "[FILE]"
			val size = if (entry.isFile) " (${entry.length()} bytes)" else ""
			val relativePath = entry.relativeTo(dir).path
			"$type $relativePath$size"
		}

		return ToolResult(name, true, output.ifEmpty { "(empty directory)" }, metadata = mapOf("count" to entries.size.toString()))
	}

	private fun searchFiles(dir: File, pattern: String, recursive: Boolean): ToolResult {
		if (!dir.exists()) return ToolResult(name, false, "", error = "Directory not found: ${dir.name}")

		val walker = if (recursive) dir.walkTopDown() else dir.walkTopDown().maxDepth(1)
		val matches = walker
			.filter { it.isFile && (it.name.contains(pattern, ignoreCase = true) || matchGlob(it.name, pattern)) }
			.take(100)
			.toList()

		val output = matches.joinToString("\n") { it.relativeTo(dir).path }
		return ToolResult(name, true, output.ifEmpty { "No files matching '$pattern'" }, metadata = mapOf("matches" to matches.size.toString()))
	}

	private fun deleteFile(file: File): ToolResult {
		if (!file.exists()) return ToolResult(name, false, "", error = "File not found: ${file.name}")

		val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
		return if (deleted) {
			logger.info("Deleted: ${file.path}")
			ToolResult(name, true, "Deleted: ${file.name}")
		} else {
			ToolResult(name, false, "", error = "Failed to delete: ${file.name}")
		}
	}

	private fun makeDirectory(dir: File): ToolResult {
		if (dir.exists()) return ToolResult(name, true, "Directory already exists: ${dir.name}")
		val created = dir.mkdirs()
		return if (created) {
			logger.info("Created directory: ${dir.path}")
			ToolResult(name, true, "Directory created: ${dir.name}")
		} else {
			ToolResult(name, false, "", error = "Failed to create directory: ${dir.name}")
		}
	}

	private fun checkExists(file: File): ToolResult {
		val exists = file.exists()
		val type = when {
			!exists -> "not found"
			file.isDirectory -> "directory"
			file.isFile -> "file"
			else -> "other"
		}
		return ToolResult(name, true, "$type: ${file.name}", metadata = mapOf("exists" to exists.toString(), "type" to type))
	}

	private fun fileInfo(file: File): ToolResult {
		if (!file.exists()) return ToolResult(name, false, "", error = "File not found: ${file.name}")

		val info = buildString {
			appendLine("Name: ${file.name}")
			appendLine("Path: ${file.path}")
			appendLine("Type: ${if (file.isDirectory) "directory" else "file"}")
			appendLine("Size: ${file.length()} bytes")
			appendLine("Readable: ${file.canRead()}")
			appendLine("Writable: ${file.canWrite()}")
			appendLine("Executable: ${file.canExecute()}")
			appendLine("Last Modified: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(file.lastModified()))}")
			if (file.isDirectory) {
				appendLine("Children: ${file.listFiles()?.size ?: 0}")
			}
		}
		return ToolResult(name, true, info)
	}

	private fun matchGlob(name: String, pattern: String): Boolean {
		val regex = pattern
			.replace(".", "\\.")
			.replace("*", ".*")
			.replace("?", ".")
		return try {
			Regex(regex, RegexOption.IGNORE_CASE).matches(name)
		} catch (_: Exception) {
			false
		}
	}
}
