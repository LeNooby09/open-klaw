package tech.lenooby09.openklaw.skills

import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.SkillsConfig
import tech.lenooby09.openklaw.tools.Tool
import tech.lenooby09.openklaw.tools.ToolParameter
import tech.lenooby09.openklaw.tools.ToolResult

/**
 * Tool that allows the agent to write and install new skills autonomously.
 *
 * When the agent encounters an unfamiliar task or recognizes a repeating pattern,
 * it can create a new skill by generating a SKILL.md file and installing it via
 * the SkillManager. Self-created skills enter PENDING status by default and
 * require admin approval before becoming active.
 */
class SkillWriterTool(
	private val config: SkillsConfig,
	private val skillManager: SkillManager
) : Tool {

	private val logger = LoggerFactory.getLogger(SkillWriterTool::class.java)

	override val name = "skill_writer"

	override val description = """Create a new skill that teaches the agent a new capability.
		|Write a SKILL.md formatted document with a name, description, instructions, and examples.
		|The skill will be saved and require admin approval before becoming active.
		|Use this when you encounter a task pattern that would benefit from a reusable skill.""".trimMargin()

	override val parameters = listOf(
		ToolParameter(
			name = "name",
			description = "Human-readable name for the skill (e.g. 'API Integration Helper')",
			required = true
		),
		ToolParameter(
			name = "id",
			description = "Unique skill identifier (alphanumeric, hyphens, underscores, 1-64 chars)",
			required = true
		),
		ToolParameter(
			name = "description",
			description = "Brief description of what the skill teaches the agent",
			required = true
		),
		ToolParameter(
			name = "instructions",
			description = "Detailed step-by-step instructions for the agent to follow",
			required = true
		),
		ToolParameter(
			name = "examples",
			description = "Example interactions demonstrating the skill in action",
			required = false,
			defaultValue = ""
		),
		ToolParameter(
			name = "context",
			description = "Additional reference material or domain knowledge",
			required = false,
			defaultValue = ""
		),
		ToolParameter(
			name = "tags",
			description = "Comma-separated tags for categorization",
			required = false,
			defaultValue = ""
		)
	)

	override val enabled: Boolean
		get() = config.enabled && config.selfImprovementEnabled

	override suspend fun execute(arguments: Map<String, String>): ToolResult {
		val skillName = arguments["name"]
			?: return ToolResult(name, false, "", "Missing required parameter: name")
		val skillId = arguments["id"]
			?: return ToolResult(name, false, "", "Missing required parameter: id")
		val skillDescription = arguments["description"]
			?: return ToolResult(name, false, "", "Missing required parameter: description")
		val skillInstructions = arguments["instructions"]
			?: return ToolResult(name, false, "", "Missing required parameter: instructions")
		val skillExamples = arguments["examples"] ?: ""
		val skillContext = arguments["context"] ?: ""
		val skillTags = arguments["tags"] ?: ""

		if (!SkillDefinition.isValidId(skillId)) {
			return ToolResult(name, false, "", "Invalid skill ID: must be 1-64 alphanumeric/hyphen/underscore characters")
		}

		// Check if skill already exists
		if (skillManager.getSkill(skillId) != null) {
			return ToolResult(name, false, "", "Skill '$skillId' already exists. Choose a different ID.")
		}

		// Build the SKILL.md content
		val content = buildString {
			appendLine("# $skillName")
			appendLine()
			appendLine("**id:** $skillId")
			appendLine("**version:** 1.0.0")
			appendLine("**author:** self-created")
			if (skillTags.isNotBlank()) {
				appendLine("**tags:** $skillTags")
			}
			appendLine()
			appendLine("## Description")
			appendLine(skillDescription)
			appendLine()
			appendLine("## Instructions")
			appendLine(skillInstructions)
			if (skillExamples.isNotBlank()) {
				appendLine()
				appendLine("## Examples")
				appendLine(skillExamples)
			}
			if (skillContext.isNotBlank()) {
				appendLine()
				appendLine("## Context")
				appendLine(skillContext)
			}
		}

		val installed = skillManager.installSkill(content, SkillSource.SELF_CREATED)
		if (installed == null) {
			return ToolResult(name, false, "", "Failed to install skill. Check that the ID is valid and the skill limit has not been reached.")
		}

		logger.info("Agent self-created skill: ${installed.name} (${installed.id})")

		return ToolResult(
			toolName = name,
			success = true,
			output = "Skill '${installed.name}' (${installed.id}) created successfully. " +
				"Status: ${installed.status}. " +
				if (installed.status == SkillStatus.PENDING)
					"The skill requires admin approval before it becomes active."
				else
					"The skill is now active.",
			metadata = mapOf(
				"skillId" to installed.id,
				"status" to installed.status.name
			)
		)
	}
}
