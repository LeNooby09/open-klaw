package tech.lenooby09.openklaw.skills

import kotlinx.serialization.Serializable

/**
 * Represents the status of a skill in the system.
 */
enum class SkillStatus {
	/** Skill loaded and awaiting admin approval. */
	PENDING,
	/** Skill approved and active — injected into agent system prompts. */
	ACTIVE,
	/** Skill explicitly disabled by admin. */
	DISABLED,
	/** Skill rejected during gating review. */
	REJECTED
}

/**
 * The source of a skill: bundled with Open-Klaw, workspace-defined, or installed from registry.
 */
enum class SkillSource {
	BUNDLED,
	WORKSPACE,
	REGISTRY,
	SELF_CREATED
}

/**
 * A structured skill definition parsed from a SKILL.md file.
 *
 * Skills teach the agent new capabilities by injecting context, instructions,
 * and examples into the system prompt. They are NOT tools — they augment the
 * agent's behavior without requiring new executable code.
 *
 * ## SKILL.md Format
 *
 * ```markdown
 * # Skill Name
 *
 * **id:** unique-skill-id
 * **version:** 1.0.0
 * **author:** author-name
 * **tags:** tag1, tag2, tag3
 *
 * ## Description
 * What this skill teaches the agent.
 *
 * ## Instructions
 * Step-by-step instructions for the agent.
 *
 * ## Examples
 * Example interactions demonstrating the skill.
 *
 * ## Context
 * Additional reference material the agent should know.
 * ```
 */
@Serializable
data class SkillDefinition(
	val id: String,
	val name: String,
	val version: String = "1.0.0",
	val author: String = "",
	val tags: List<String> = emptyList(),
	val description: String = "",
	val instructions: String = "",
	val examples: String = "",
	val context: String = "",
	val source: SkillSource = SkillSource.WORKSPACE,
	val status: SkillStatus = SkillStatus.PENDING,
	val filePath: String = "",
	val installedAt: Long = System.currentTimeMillis()
) {

	companion object {
		private val ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
		private val VERSION_REGEX = Regex("^\\d+\\.\\d+\\.\\d+$")

		fun isValidId(id: String): Boolean = ID_REGEX.matches(id)

		/**
		 * Parse a SKILL.md file content into a SkillDefinition.
		 * Returns null if the content is malformed or missing required fields.
		 */
		fun parse(content: String, source: SkillSource = SkillSource.WORKSPACE, filePath: String = ""): SkillDefinition? {
			if (content.isBlank()) return null

			val lines = content.lines()

			// Extract name from first H1 heading
			val name = lines.firstOrNull { it.startsWith("# ") }
				?.removePrefix("# ")?.trim()
				?: return null

			// Extract metadata fields from **key:** value pattern
			val metadataMap = mutableMapOf<String, String>()
			for (line in lines) {
				val match = Regex("^\\*\\*([a-zA-Z]+):\\*\\*\\s*(.+)$").find(line.trim())
				if (match != null) {
					metadataMap[match.groupValues[1].lowercase()] = match.groupValues[2].trim()
				}
			}

			val id = metadataMap["id"] ?: name.lowercase().replace(Regex("[^a-zA-Z0-9_-]"), "-").take(64)
			if (!isValidId(id)) return null

			val version = metadataMap["version"] ?: "1.0.0"
			val author = metadataMap["author"] ?: ""
			val tags = metadataMap["tags"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

			// Extract sections by ## headings
			val sections = parseSections(content)

			return SkillDefinition(
				id = id,
				name = name,
				version = version,
				author = author,
				tags = tags,
				description = sections["description"] ?: "",
				instructions = sections["instructions"] ?: "",
				examples = sections["examples"] ?: "",
				context = sections["context"] ?: "",
				source = source,
				status = if (source == SkillSource.BUNDLED) SkillStatus.ACTIVE else SkillStatus.PENDING,
				filePath = filePath
			)
		}

		/**
		 * Parse ## sections from markdown content.
		 * Returns a map of lowercase section name → content.
		 */
		private fun parseSections(content: String): Map<String, String> {
			val sections = mutableMapOf<String, String>()
			val lines = content.lines()
			var currentSection: String? = null
			val currentContent = StringBuilder()

			for (line in lines) {
				if (line.startsWith("## ")) {
					// Save previous section
					if (currentSection != null) {
						sections[currentSection] = currentContent.toString().trim()
					}
					currentSection = line.removePrefix("## ").trim().lowercase()
					currentContent.clear()
				} else if (currentSection != null) {
					currentContent.appendLine(line)
				}
			}

			// Save last section
			if (currentSection != null) {
				sections[currentSection] = currentContent.toString().trim()
			}

			return sections
		}
	}

	/**
	 * Build a system prompt section for this skill.
	 * Only active skills should be included in prompts.
	 */
	fun buildPromptSection(): String {
		if (status != SkillStatus.ACTIVE) return ""

		return buildString {
			appendLine("\n### Skill: $name (v$version)")
			if (description.isNotBlank()) {
				appendLine(description)
			}
			if (instructions.isNotBlank()) {
				appendLine("\n**Instructions:**")
				appendLine(instructions)
			}
			if (examples.isNotBlank()) {
				appendLine("\n**Examples:**")
				appendLine(examples)
			}
			if (context.isNotBlank()) {
				appendLine("\n**Reference:**")
				appendLine(context)
			}
		}
	}
}
