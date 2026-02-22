package tech.lenooby09.openklaw.tools

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.ToolsConfig
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * A canvas item pushed to the display surface by the agent.
 */
@Serializable
data class CanvasItem(
	val id: String = UUID.randomUUID().toString(),
	val type: String,
	val title: String = "",
	val content: String,
	val mimeType: String = "text/html",
	val createdAt: Long = System.currentTimeMillis()
)

/**
 * Canvas / UI Surface tool. Allows the agent to push rich content (HTML, Markdown,
 * images, interactive elements) to a display surface that can be rendered by the
 * dashboard or any connected client.
 */
class CanvasTool(private val config: ToolsConfig) : Tool {
	private val logger = LoggerFactory.getLogger(CanvasTool::class.java)
	private val canvasItems = ConcurrentHashMap<String, CanvasItem>()

	override val name = "canvas"
	override val description = "Push rich content (HTML, Markdown, images, interactive elements) to the display surface for the user to view."
	override val parameters = listOf(
		ToolParameter("action", "Action: push, update, remove, clear, list", type = "string", required = true),
		ToolParameter("type", "Content type: html, markdown, image, code, table, chart", type = "string", required = false, defaultValue = "html"),
		ToolParameter("title", "Title for the canvas item", type = "string", required = false, defaultValue = ""),
		ToolParameter("content", "The content to display (HTML, Markdown, image URL, code, etc.)", type = "string", required = false),
		ToolParameter("id", "Canvas item ID (for update/remove operations)", type = "string", required = false),
		ToolParameter("mimeType", "MIME type of the content", type = "string", required = false, defaultValue = "text/html")
	)
	override val enabled: Boolean get() = config.canvasEnabled

	override suspend fun execute(arguments: Map<String, String>): ToolResult {
		val action = arguments["action"] ?: return ToolResult(name, false, "", error = "Missing 'action' argument")

		return when (action.lowercase()) {
			"push" -> pushItem(arguments)
			"update" -> updateItem(arguments)
			"remove" -> removeItem(arguments)
			"clear" -> clearCanvas()
			"list" -> listItems()
			else -> ToolResult(name, false, "", error = "Unknown action: $action. Use: push, update, remove, clear, list")
		}
	}

	private fun pushItem(args: Map<String, String>): ToolResult {
		val content = args["content"] ?: return ToolResult(name, false, "", error = "Missing 'content' argument for push")
		val type = args["type"] ?: "html"
		val title = args["title"] ?: ""
		val mimeType = args["mimeType"] ?: resolveMimeType(type)

		val item = CanvasItem(
			type = type,
			title = title,
			content = content,
			mimeType = mimeType
		)
		canvasItems[item.id] = item
		logger.info("Canvas item pushed: ${item.id} (type=$type, title=$title)")

		return ToolResult(
			name, true,
			"Canvas item created: ${item.id}",
			metadata = mapOf("id" to item.id, "type" to type)
		)
	}

	private fun updateItem(args: Map<String, String>): ToolResult {
		val id = args["id"] ?: return ToolResult(name, false, "", error = "Missing 'id' argument for update")
		val existing = canvasItems[id] ?: return ToolResult(name, false, "", error = "Canvas item not found: $id")

		val updated = existing.copy(
			content = args["content"] ?: existing.content,
			title = args["title"] ?: existing.title,
			type = args["type"] ?: existing.type,
			mimeType = args["mimeType"] ?: existing.mimeType
		)
		canvasItems[id] = updated
		logger.info("Canvas item updated: $id")

		return ToolResult(name, true, "Canvas item updated: $id", metadata = mapOf("id" to id))
	}

	private fun removeItem(args: Map<String, String>): ToolResult {
		val id = args["id"] ?: return ToolResult(name, false, "", error = "Missing 'id' argument for remove")
		val removed = canvasItems.remove(id) != null
		return if (removed) {
			logger.info("Canvas item removed: $id")
			ToolResult(name, true, "Canvas item removed: $id")
		} else {
			ToolResult(name, false, "", error = "Canvas item not found: $id")
		}
	}

	private fun clearCanvas(): ToolResult {
		val count = canvasItems.size
		canvasItems.clear()
		logger.info("Canvas cleared ($count items)")
		return ToolResult(name, true, "Canvas cleared ($count items removed)")
	}

	private fun listItems(): ToolResult {
		if (canvasItems.isEmpty()) {
			return ToolResult(name, true, "(canvas is empty)")
		}

		val output = canvasItems.values
			.sortedByDescending { it.createdAt }
			.joinToString("\n") { item ->
				"[${item.id}] type=${item.type} title=\"${item.title}\" (${item.content.length} chars)"
			}

		return ToolResult(name, true, output, metadata = mapOf("count" to canvasItems.size.toString()))
	}

	fun getItems(): List<CanvasItem> = canvasItems.values.sortedByDescending { it.createdAt }

	fun getItem(id: String): CanvasItem? = canvasItems[id]

	private fun resolveMimeType(type: String): String = when (type.lowercase()) {
		"html" -> "text/html"
		"markdown" -> "text/markdown"
		"image" -> "image/png"
		"code" -> "text/plain"
		"table" -> "text/html"
		"chart" -> "application/json"
		else -> "text/html"
	}
}
