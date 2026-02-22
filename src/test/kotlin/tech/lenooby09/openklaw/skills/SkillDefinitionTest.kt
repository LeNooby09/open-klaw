package tech.lenooby09.openklaw.skills

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SkillDefinitionTest {

	private val validSkillMd = """
		# Test Skill

		**id:** test-skill
		**version:** 2.0.0
		**author:** tester
		**tags:** test, demo, example

		## Description
		A test skill for unit testing.

		## Instructions
		1. Do step one
		2. Do step two

		## Examples
		User: "test something"
		Agent: does the test

		## Context
		Some reference material here.
	""".trimIndent()

	@Test
	fun `parse valid SKILL md`() {
		val skill = SkillDefinition.parse(validSkillMd)
		assertNotNull(skill)
		assertEquals("test-skill", skill!!.id)
		assertEquals("Test Skill", skill.name)
		assertEquals("2.0.0", skill.version)
		assertEquals("tester", skill.author)
		assertEquals(listOf("test", "demo", "example"), skill.tags)
		assertTrue(skill.description.contains("test skill for unit testing"))
		assertTrue(skill.instructions.contains("step one"))
		assertTrue(skill.examples.contains("test something"))
		assertTrue(skill.context.contains("reference material"))
		assertEquals(SkillSource.WORKSPACE, skill.source)
		assertEquals(SkillStatus.PENDING, skill.status)
	}

	@Test
	fun `parse minimal SKILL md`() {
		val minimal = """
			# Minimal Skill

			## Description
			Just a description.
		""".trimIndent()

		val skill = SkillDefinition.parse(minimal)
		assertNotNull(skill)
		assertEquals("minimal-skill", skill!!.id) // auto-generated from name
		assertEquals("Minimal Skill", skill.name)
		assertEquals("1.0.0", skill.version)
		assertEquals("", skill.author)
		assertEquals(emptyList<String>(), skill.tags)
		assertTrue(skill.description.contains("Just a description"))
	}

	@Test
	fun `parse returns null for blank content`() {
		assertNull(SkillDefinition.parse(""))
		assertNull(SkillDefinition.parse("   "))
	}

	@Test
	fun `parse returns null for missing H1 heading`() {
		val noHeading = """
			**id:** no-heading
			## Description
			Content without a title.
		""".trimIndent()

		assertNull(SkillDefinition.parse(noHeading))
	}

	@Test
	fun `parse bundled skill sets ACTIVE status`() {
		val skill = SkillDefinition.parse(validSkillMd, SkillSource.BUNDLED)
		assertNotNull(skill)
		assertEquals(SkillStatus.ACTIVE, skill!!.status)
		assertEquals(SkillSource.BUNDLED, skill.source)
	}

	@Test
	fun `parse workspace skill sets PENDING status`() {
		val skill = SkillDefinition.parse(validSkillMd, SkillSource.WORKSPACE)
		assertNotNull(skill)
		assertEquals(SkillStatus.PENDING, skill!!.status)
	}

	@Test
	fun `parse preserves filePath`() {
		val skill = SkillDefinition.parse(validSkillMd, filePath = "/some/path/skill.md")
		assertNotNull(skill)
		assertEquals("/some/path/skill.md", skill!!.filePath)
	}

	@Test
	fun `isValidId accepts valid IDs`() {
		assertTrue(SkillDefinition.isValidId("web-research"))
		assertTrue(SkillDefinition.isValidId("my_skill_01"))
		assertTrue(SkillDefinition.isValidId("A"))
		assertTrue(SkillDefinition.isValidId("a".repeat(64)))
	}

	@Test
	fun `isValidId rejects invalid IDs`() {
		assertFalse(SkillDefinition.isValidId(""))
		assertFalse(SkillDefinition.isValidId("has spaces"))
		assertFalse(SkillDefinition.isValidId("special!chars"))
		assertFalse(SkillDefinition.isValidId("a".repeat(65)))
	}

	@Test
	fun `buildPromptSection returns empty for non-active skill`() {
		val skill = SkillDefinition.parse(validSkillMd)!!
		assertEquals("", skill.buildPromptSection())
	}

	@Test
	fun `buildPromptSection includes content for active skill`() {
		val skill = SkillDefinition.parse(validSkillMd, SkillSource.BUNDLED)!!
		val prompt = skill.buildPromptSection()
		assertTrue(prompt.contains("Test Skill"))
		assertTrue(prompt.contains("v2.0.0"))
		assertTrue(prompt.contains("Instructions"))
		assertTrue(prompt.contains("Examples"))
		assertTrue(prompt.contains("Reference"))
	}

	@Test
	fun `parse all bundled skills successfully`() {
		for (content in BundledSkills.ALL) {
			val skill = SkillDefinition.parse(content, SkillSource.BUNDLED)
			assertNotNull(skill, "Bundled skill failed to parse: ${content.take(50)}")
			assertTrue(skill!!.id.isNotBlank())
			assertTrue(skill.name.isNotBlank())
			assertTrue(skill.description.isNotBlank())
			assertTrue(skill.instructions.isNotBlank())
			assertEquals(SkillStatus.ACTIVE, skill.status)
		}
	}

	@Test
	fun `parse handles multiple sections correctly`() {
		val multiSection = """
			# Multi Section Skill

			**id:** multi-section

			## Description
			First section content.

			## Instructions
			Second section content.

			## Context
			Third section content.
		""".trimIndent()

		val skill = SkillDefinition.parse(multiSection)
		assertNotNull(skill)
		assertEquals("First section content.", skill!!.description)
		assertEquals("Second section content.", skill.instructions)
		assertEquals("Third section content.", skill.context)
		assertEquals("", skill.examples) // Missing section defaults to empty
	}
}
