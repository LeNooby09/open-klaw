package tech.lenooby09.openklaw.skills

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tech.lenooby09.openklaw.config.SkillsConfig
import java.io.File

class SkillManagerTest {

	@TempDir
	lateinit var tempDir: File

	private lateinit var config: SkillsConfig
	private lateinit var manager: SkillManager

	@BeforeEach
	fun setup() {
		config = SkillsConfig(
			enabled = true,
			dataDir = File(tempDir, "data").absolutePath,
			workspaceSkillsDir = File(tempDir, "skills").absolutePath,
			maxSkills = 200,
			autoApproveWorkspaceSkills = false
		)
		manager = SkillManager(config)
		manager.initialize()
	}

	@Test
	fun `initialize loads bundled skills`() {
		val skills = manager.getAllSkills()
		assertTrue(skills.any { it.id == "web-research" })
		assertTrue(skills.any { it.id == "file-management" })
		assertTrue(skills.any { it.id == "coding-assistance" })
	}

	@Test
	fun `bundled skills are auto-active`() {
		val bundled = manager.getSkillsBySource(SkillSource.BUNDLED)
		assertTrue(bundled.isNotEmpty())
		assertTrue(bundled.all { it.status == SkillStatus.ACTIVE })
	}

	@Test
	fun `initialize creates directories`() {
		assertTrue(File(config.dataDir, "skills").exists())
		assertTrue(File(config.workspaceSkillsDir).exists())
	}

	@Test
	fun `load workspace skills from directory`() {
		val skillFile = File(config.workspaceSkillsDir, "custom.md")
		skillFile.writeText("""
			# Custom Skill
			
			**id:** custom-skill
			**version:** 1.0.0
			
			## Description
			A custom workspace skill.
			
			## Instructions
			Do custom things.
		""".trimIndent())

		// Re-initialize to pick up the new file
		val manager2 = SkillManager(config)
		manager2.initialize()

		val skill = manager2.getSkill("custom-skill")
		assertNotNull(skill)
		assertEquals("Custom Skill", skill!!.name)
		assertEquals(SkillSource.WORKSPACE, skill.source)
		assertEquals(SkillStatus.PENDING, skill.status)
	}

	@Test
	fun `auto-approve workspace skills when configured`() {
		val autoConfig = config.copy(autoApproveWorkspaceSkills = true)
		val skillFile = File(autoConfig.workspaceSkillsDir, "auto.md")
		File(autoConfig.workspaceSkillsDir).mkdirs()
		skillFile.writeText("""
			# Auto Skill
			
			**id:** auto-skill
			
			## Description
			Auto-approved skill.
		""".trimIndent())

		val autoManager = SkillManager(autoConfig)
		autoManager.initialize()

		val skill = autoManager.getSkill("auto-skill")
		assertNotNull(skill)
		assertEquals(SkillStatus.ACTIVE, skill!!.status)
	}

	@Test
	fun `approve pending skill`() {
		val content = """
			# Pending Skill
			
			**id:** pending-skill
			
			## Description
			A pending skill.
		""".trimIndent()

		manager.installSkill(content, SkillSource.WORKSPACE)
		assertEquals(SkillStatus.PENDING, manager.getSkill("pending-skill")!!.status)

		assertTrue(manager.approveSkill("pending-skill"))
		assertEquals(SkillStatus.ACTIVE, manager.getSkill("pending-skill")!!.status)
	}

	@Test
	fun `reject pending skill`() {
		val content = """
			# Reject Skill
			
			**id:** reject-skill
			
			## Description
			Will be rejected.
		""".trimIndent()

		manager.installSkill(content, SkillSource.WORKSPACE)
		assertTrue(manager.rejectSkill("reject-skill"))
		assertEquals(SkillStatus.REJECTED, manager.getSkill("reject-skill")!!.status)
	}

	@Test
	fun `disable active skill`() {
		val skill = manager.getActiveSkills().first()
		assertTrue(manager.disableSkill(skill.id))
		assertEquals(SkillStatus.DISABLED, manager.getSkill(skill.id)!!.status)
	}

	@Test
	fun `enable disabled skill`() {
		val skill = manager.getActiveSkills().first()
		manager.disableSkill(skill.id)
		assertTrue(manager.enableSkill(skill.id))
		assertEquals(SkillStatus.ACTIVE, manager.getSkill(skill.id)!!.status)
	}

	@Test
	fun `enable rejected skill`() {
		val content = """
			# Re-enable Skill
			
			**id:** re-enable-skill
			
			## Description
			Rejected then re-enabled.
		""".trimIndent()

		manager.installSkill(content, SkillSource.WORKSPACE)
		manager.rejectSkill("re-enable-skill")
		assertTrue(manager.enableSkill("re-enable-skill"))
		assertEquals(SkillStatus.ACTIVE, manager.getSkill("re-enable-skill")!!.status)
	}

	@Test
	fun `cannot approve non-pending skill`() {
		val skill = manager.getActiveSkills().first() // Already ACTIVE
		assertFalse(manager.approveSkill(skill.id))
	}

	@Test
	fun `cannot remove bundled skill`() {
		val bundled = manager.getSkillsBySource(SkillSource.BUNDLED).first()
		assertFalse(manager.removeSkill(bundled.id))
	}

	@Test
	fun `remove non-bundled skill`() {
		val content = """
			# Removable Skill
			
			**id:** removable-skill
			
			## Description
			Will be removed.
		""".trimIndent()

		manager.installSkill(content, SkillSource.WORKSPACE)
		assertNotNull(manager.getSkill("removable-skill"))

		assertTrue(manager.removeSkill("removable-skill"))
		assertNull(manager.getSkill("removable-skill"))
	}

	@Test
	fun `install skill respects max limit`() {
		val limitConfig = config.copy(maxSkills = 3) // Only bundled skills fit
		val limitManager = SkillManager(limitConfig)
		limitManager.initialize()

		val content = """
			# Over Limit
			
			**id:** over-limit
			
			## Description
			Should not install.
		""".trimIndent()

		assertNull(limitManager.installSkill(content, SkillSource.WORKSPACE))
	}

	@Test
	fun `install skill rejects oversized content`() {
		val huge = "# Big Skill\n\n**id:** big-skill\n\n## Description\n" + "x".repeat(300_000)
		assertNull(manager.installSkill(huge, SkillSource.WORKSPACE))
	}

	@Test
	fun `install skill rejects invalid content`() {
		assertNull(manager.installSkill("not a valid skill", SkillSource.WORKSPACE))
	}

	@Test
	fun `install skill with auto-approve`() {
		val content = """
			# Auto Approved
			
			**id:** auto-approved
			
			## Description
			Installed with auto-approve.
		""".trimIndent()

		val installed = manager.installSkill(content, SkillSource.REGISTRY, autoApprove = true)
		assertNotNull(installed)
		assertEquals(SkillStatus.ACTIVE, installed!!.status)
	}

	@Test
	fun `cannot install over bundled skill`() {
		val content = """
			# Web Research
			
			**id:** web-research
			
			## Description
			Trying to overwrite bundled.
		""".trimIndent()

		assertNull(manager.installSkill(content, SkillSource.WORKSPACE))
	}

	@Test
	fun `search skills by name`() {
		val results = manager.searchSkills("web")
		assertTrue(results.any { it.id == "web-research" })
	}

	@Test
	fun `search skills by tag`() {
		val results = manager.searchSkills("coding")
		assertTrue(results.any { it.id == "coding-assistance" })
	}

	@Test
	fun `get skills by tag`() {
		val results = manager.getSkillsByTag("research")
		assertTrue(results.any { it.id == "web-research" })
	}

	@Test
	fun `buildSkillsContext includes active skills`() {
		val context = manager.buildSkillsContext()
		assertTrue(context.contains("SKILLS CONTEXT"))
		assertTrue(context.contains("Web Research"))
		assertTrue(context.contains("File Management"))
		assertTrue(context.contains("Coding Assistance"))
	}

	@Test
	fun `buildSkillsContext empty when disabled`() {
		val disabledConfig = config.copy(enabled = false)
		val disabledManager = SkillManager(disabledConfig)
		assertEquals("", disabledManager.buildSkillsContext())
	}

	@Test
	fun `manifest persists skill status across reinitialize`() {
		val content = """
			# Persist Skill
			
			**id:** persist-skill
			
			## Description
			Testing persistence.
		""".trimIndent()

		manager.installSkill(content, SkillSource.WORKSPACE)
		manager.approveSkill("persist-skill")

		// Re-create manager and reinitialize
		val manager2 = SkillManager(config)
		manager2.initialize()

		val skill = manager2.getSkill("persist-skill")
		assertNotNull(skill)
		assertEquals(SkillStatus.ACTIVE, skill!!.status)
	}

	@Test
	fun `getActiveSkills returns only active`() {
		val active = manager.getActiveSkills()
		assertTrue(active.all { it.status == SkillStatus.ACTIVE })
	}

	@Test
	fun `getPendingSkills returns only pending`() {
		val content = """
			# Pending Test
			
			**id:** pending-test
			
			## Description
			Pending.
		""".trimIndent()

		manager.installSkill(content, SkillSource.WORKSPACE)
		val pending = manager.getPendingSkills()
		assertTrue(pending.all { it.status == SkillStatus.PENDING })
		assertTrue(pending.any { it.id == "pending-test" })
	}
}
