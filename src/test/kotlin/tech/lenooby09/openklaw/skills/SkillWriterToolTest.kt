package tech.lenooby09.openklaw.skills

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tech.lenooby09.openklaw.config.SkillsConfig
import java.io.File

class SkillWriterToolTest {

	@TempDir
	lateinit var tempDir: File

	private lateinit var config: SkillsConfig
	private lateinit var manager: SkillManager
	private lateinit var tool: SkillWriterTool

	@BeforeEach
	fun setup() {
		config = SkillsConfig(
			enabled = true,
			dataDir = File(tempDir, "data").absolutePath,
			workspaceSkillsDir = File(tempDir, "skills").absolutePath,
			selfImprovementEnabled = true
		)
		manager = SkillManager(config)
		manager.initialize()
		tool = SkillWriterTool(config, manager)
	}

	@Test
	fun `tool is enabled when config allows`() {
		assertTrue(tool.enabled)
	}

	@Test
	fun `tool is disabled when self-improvement disabled`() {
		val disabledConfig = config.copy(selfImprovementEnabled = false)
		val disabledTool = SkillWriterTool(disabledConfig, manager)
		assertFalse(disabledTool.enabled)
	}

	@Test
	fun `tool is disabled when skills platform disabled`() {
		val disabledConfig = config.copy(enabled = false)
		val disabledTool = SkillWriterTool(disabledConfig, manager)
		assertFalse(disabledTool.enabled)
	}

	@Test
	fun `create skill successfully`() = runBlocking {
		val result = tool.execute(mapOf(
			"name" to "Test Helper",
			"id" to "test-helper",
			"description" to "Helps with testing",
			"instructions" to "Write good tests"
		))

		assertTrue(result.success)
		assertTrue(result.output.contains("test-helper"))
		assertTrue(result.output.contains("PENDING"))

		val skill = manager.getSkill("test-helper")
		assertNotNull(skill)
		assertEquals("Test Helper", skill!!.name)
		assertEquals(SkillSource.SELF_CREATED, skill.source)
		assertEquals(SkillStatus.PENDING, skill.status)
	}

	@Test
	fun `create skill with all parameters`() = runBlocking {
		val result = tool.execute(mapOf(
			"name" to "Full Skill",
			"id" to "full-skill",
			"description" to "A complete skill",
			"instructions" to "Follow all steps",
			"examples" to "Example interaction here",
			"context" to "Background info",
			"tags" to "test, demo"
		))

		assertTrue(result.success)

		val skill = manager.getSkill("full-skill")
		assertNotNull(skill)
		assertTrue(skill!!.description.contains("complete skill"))
		assertTrue(skill.instructions.contains("Follow all steps"))
		assertTrue(skill.examples.contains("Example interaction"))
		assertTrue(skill.context.contains("Background info"))
	}

	@Test
	fun `fail with missing name`() = runBlocking {
		val result = tool.execute(mapOf(
			"id" to "no-name",
			"description" to "Missing name",
			"instructions" to "Something"
		))

		assertFalse(result.success)
		assertTrue(result.error!!.contains("name"))
	}

	@Test
	fun `fail with missing id`() = runBlocking {
		val result = tool.execute(mapOf(
			"name" to "No ID",
			"description" to "Missing ID",
			"instructions" to "Something"
		))

		assertFalse(result.success)
		assertTrue(result.error!!.contains("id"))
	}

	@Test
	fun `fail with invalid id`() = runBlocking {
		val result = tool.execute(mapOf(
			"name" to "Bad ID",
			"id" to "has spaces here",
			"description" to "Invalid",
			"instructions" to "Something"
		))

		assertFalse(result.success)
		assertTrue(result.error!!.contains("Invalid skill ID"))
	}

	@Test
	fun `fail when skill already exists`() = runBlocking {
		tool.execute(mapOf(
			"name" to "First",
			"id" to "duplicate-skill",
			"description" to "First version",
			"instructions" to "V1"
		))

		val result = tool.execute(mapOf(
			"name" to "Second",
			"id" to "duplicate-skill",
			"description" to "Second version",
			"instructions" to "V2"
		))

		assertFalse(result.success)
		assertTrue(result.error!!.contains("already exists"))
	}

	@Test
	fun `cannot overwrite bundled skill`() = runBlocking {
		val result = tool.execute(mapOf(
			"name" to "Web Research Override",
			"id" to "web-research",
			"description" to "Override bundled",
			"instructions" to "Bad"
		))

		assertFalse(result.success)
		assertTrue(result.error!!.contains("already exists"))
	}

	@Test
	fun `tool has correct name and parameters`() {
		assertEquals("skill_writer", tool.name)
		assertTrue(tool.parameters.any { it.name == "name" && it.required })
		assertTrue(tool.parameters.any { it.name == "id" && it.required })
		assertTrue(tool.parameters.any { it.name == "description" && it.required })
		assertTrue(tool.parameters.any { it.name == "instructions" && it.required })
		assertTrue(tool.parameters.any { it.name == "examples" && !it.required })
		assertTrue(tool.parameters.any { it.name == "context" && !it.required })
		assertTrue(tool.parameters.any { it.name == "tags" && !it.required })
	}

	@Test
	fun `skill file is persisted on disk`() = runBlocking {
		tool.execute(mapOf(
			"name" to "Persisted",
			"id" to "persisted-skill",
			"description" to "Check disk",
			"instructions" to "Steps"
		))

		val selfCreatedDir = File(config.dataDir, "skills/self-created")
		assertTrue(selfCreatedDir.exists())
		val files = selfCreatedDir.listFiles()?.map { it.name } ?: emptyList()
		assertTrue("persisted-skill.md" in files)
	}
}
