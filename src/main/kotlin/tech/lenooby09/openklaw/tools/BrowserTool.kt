package tech.lenooby09.openklaw.tools

import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.ToolsConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Headless/headed browser automation tool. Supports navigation, content extraction,
 * form interaction, and page snapshots. Uses a lightweight HTTP-based approach
 * for fetching and processing web content, with optional integration points
 * for full browser engines (Playwright/Selenium) in the future.
 */
class BrowserTool(private val config: ToolsConfig) : Tool {
	private val logger = LoggerFactory.getLogger(BrowserTool::class.java)

	override val name = "browser"
	override val description = "Browse the web: navigate to URLs, extract text content, take snapshots, and interact with pages."
	override val parameters = listOf(
		ToolParameter("action", "Action: navigate, screenshot, extract_text, extract_links, fill_form, click, execute_js", type = "string", required = true),
		ToolParameter("url", "URL to navigate to or interact with", type = "string", required = false),
		ToolParameter("selector", "CSS selector for targeting elements (click, fill_form)", type = "string", required = false),
		ToolParameter("value", "Value to fill into a form field", type = "string", required = false),
		ToolParameter("javascript", "JavaScript code to execute on the page (execute_js action)", type = "string", required = false),
		ToolParameter("outputPath", "File path to save screenshot output", type = "string", required = false)
	)
	override val enabled: Boolean get() = config.browserEnabled

	companion object {
		const val MAX_CONTENT_LENGTH = 100_000
		private val ALLOWED_SCHEMES = setOf("http", "https")
	}

	override suspend fun execute(arguments: Map<String, String>): ToolResult {
		val action = arguments["action"] ?: return ToolResult(name, false, "", error = "Missing 'action' argument")

		return when (action.lowercase()) {
			"navigate" -> navigate(arguments)
			"extract_text" -> extractText(arguments)
			"extract_links" -> extractLinks(arguments)
			"screenshot" -> screenshot(arguments)
			"fill_form" -> fillForm(arguments)
			"click" -> click(arguments)
			"execute_js" -> executeJs(arguments)
			else -> ToolResult(name, false, "", error = "Unknown action: $action. Use: navigate, extract_text, extract_links, screenshot, fill_form, click, execute_js")
		}
	}

	private fun navigate(args: Map<String, String>): ToolResult {
		val url = args["url"] ?: return ToolResult(name, false, "", error = "Missing 'url' argument")
		if (!isValidUrl(url)) return ToolResult(name, false, "", error = "Invalid or disallowed URL: $url")

		return try {
			val connection = URI(url).toURL().openConnection() as HttpURLConnection
			connection.requestMethod = "GET"
			connection.connectTimeout = (config.browserTimeoutSeconds * 1000).toInt()
			connection.readTimeout = (config.browserTimeoutSeconds * 1000).toInt()
			connection.setRequestProperty("User-Agent", "Open-Klaw/1.0 (Browser Tool)")
			connection.instanceFollowRedirects = true

			val responseCode = connection.responseCode
			val contentType = connection.contentType ?: "unknown"
			val content = if (responseCode in 200..299) {
				connection.inputStream.bufferedReader().readText().take(MAX_CONTENT_LENGTH)
			} else {
				connection.errorStream?.bufferedReader()?.readText()?.take(MAX_CONTENT_LENGTH) ?: ""
			}

			ToolResult(
				toolName = name,
				success = responseCode in 200..299,
				output = content,
				error = if (responseCode !in 200..299) "HTTP $responseCode" else null,
				metadata = mapOf(
					"statusCode" to responseCode.toString(),
					"contentType" to contentType,
					"url" to url
				)
			)
		} catch (e: Exception) {
			logger.error("Browser navigate failed: ${e.message}")
			ToolResult(name, false, "", error = "Navigation failed: ${e.message}")
		}
	}

	private fun extractText(args: Map<String, String>): ToolResult {
		val url = args["url"] ?: return ToolResult(name, false, "", error = "Missing 'url' argument")
		if (!isValidUrl(url)) return ToolResult(name, false, "", error = "Invalid or disallowed URL: $url")

		return try {
			val connection = URI(url).toURL().openConnection() as HttpURLConnection
			connection.connectTimeout = (config.browserTimeoutSeconds * 1000).toInt()
			connection.readTimeout = (config.browserTimeoutSeconds * 1000).toInt()
			connection.setRequestProperty("User-Agent", "Open-Klaw/1.0 (Browser Tool)")

			val html = connection.inputStream.bufferedReader().readText().take(MAX_CONTENT_LENGTH)
			val text = stripHtmlTags(html)

			ToolResult(name, true, text, metadata = mapOf("url" to url, "originalLength" to html.length.toString()))
		} catch (e: Exception) {
			ToolResult(name, false, "", error = "Text extraction failed: ${e.message}")
		}
	}

	private fun extractLinks(args: Map<String, String>): ToolResult {
		val url = args["url"] ?: return ToolResult(name, false, "", error = "Missing 'url' argument")
		if (!isValidUrl(url)) return ToolResult(name, false, "", error = "Invalid or disallowed URL: $url")

		return try {
			val connection = URI(url).toURL().openConnection() as HttpURLConnection
			connection.connectTimeout = (config.browserTimeoutSeconds * 1000).toInt()
			connection.readTimeout = (config.browserTimeoutSeconds * 1000).toInt()
			connection.setRequestProperty("User-Agent", "Open-Klaw/1.0 (Browser Tool)")

			val html = connection.inputStream.bufferedReader().readText().take(MAX_CONTENT_LENGTH)
			val links = extractLinksFromHtml(html)

			ToolResult(name, true, links.joinToString("\n"), metadata = mapOf("url" to url, "linkCount" to links.size.toString()))
		} catch (e: Exception) {
			ToolResult(name, false, "", error = "Link extraction failed: ${e.message}")
		}
	}

	private fun screenshot(args: Map<String, String>): ToolResult {
		val url = args["url"] ?: return ToolResult(name, false, "", error = "Missing 'url' argument")
		val outputPath = args["outputPath"] ?: "screenshot.html"

		if (!isValidUrl(url)) return ToolResult(name, false, "", error = "Invalid or disallowed URL: $url")

		return try {
			val connection = URI(url).toURL().openConnection() as HttpURLConnection
			connection.connectTimeout = (config.browserTimeoutSeconds * 1000).toInt()
			connection.readTimeout = (config.browserTimeoutSeconds * 1000).toInt()
			connection.setRequestProperty("User-Agent", "Open-Klaw/1.0 (Browser Tool)")

			val html = connection.inputStream.bufferedReader().readText().take(MAX_CONTENT_LENGTH)
			File(outputPath).writeText(html)

			ToolResult(
				name, true,
				"Page snapshot saved to: $outputPath (${html.length} chars)",
				metadata = mapOf("url" to url, "outputPath" to outputPath)
			)
		} catch (e: Exception) {
			ToolResult(name, false, "", error = "Screenshot failed: ${e.message}")
		}
	}

	private fun fillForm(args: Map<String, String>): ToolResult {
		return ToolResult(
			name, false, "",
			error = "fill_form requires a full browser engine (Playwright/Selenium). " +
				"This will be available when a browser driver is configured. " +
				"For now, use the shell tool with curl for form submissions."
		)
	}

	private fun click(args: Map<String, String>): ToolResult {
		return ToolResult(
			name, false, "",
			error = "click requires a full browser engine (Playwright/Selenium). " +
				"This will be available when a browser driver is configured. " +
				"For now, use navigate to fetch page content directly."
		)
	}

	private fun executeJs(args: Map<String, String>): ToolResult {
		return ToolResult(
			name, false, "",
			error = "execute_js requires a full browser engine (Playwright/Selenium). " +
				"This will be available when a browser driver is configured."
		)
	}

	private fun isValidUrl(url: String): Boolean {
		return try {
			val uri = URI(url)
			uri.scheme?.lowercase() in ALLOWED_SCHEMES
		} catch (_: Exception) {
			false
		}
	}

	private fun stripHtmlTags(html: String): String {
		return html
			.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
			.replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
			.replace(Regex("<[^>]+>"), " ")
			.replace(Regex("&nbsp;"), " ")
			.replace(Regex("&amp;"), "&")
			.replace(Regex("&lt;"), "<")
			.replace(Regex("&gt;"), ">")
			.replace(Regex("&quot;"), "\"")
			.replace(Regex("\\s+"), " ")
			.trim()
	}

	private fun extractLinksFromHtml(html: String): List<String> {
		val linkPattern = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
		return linkPattern.findAll(html)
			.map { it.groupValues[1] }
			.filter { it.startsWith("http") }
			.distinct()
			.take(100)
			.toList()
	}
}
