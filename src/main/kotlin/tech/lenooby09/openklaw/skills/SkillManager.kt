package tech.lenooby09.openklaw.skills

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.SkillsConfig
import java.io.File
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ConcurrentHashMap

/**
 * Central manager for the skill/plugin ecosystem.
 *
 * Responsibilities:
 * - Load bundled skills from classpath resources
 * - Load workspace skills from the workspace skills directory
 * - Load registry-installed skills from the data skills directory
 * - Enforce install gating (pending → approved/rejected workflow)
 * - Persist skill status to a JSON manifest file
 * - Build skill context for injection into agent system prompts
 * - Support skill CRUD operations via REST API
 */
class SkillManager(private val config: SkillsConfig) {
	private val logger = LoggerFactory.getLogger(SkillManager::class.java)
	private val skills = ConcurrentHashMap<String, SkillDefinition>()
	private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

	private val skillsDir = File(config.dataDir, "skills")
	private val workspaceSkillsDir = File(config.workspaceSkillsDir)
	private val manifestFile = File(skillsDir, "manifest.json")

	companion object {
		const val MAX_SKILL_FILE_SIZE_BYTES = 256 * 1024L // 256 KB

		/** Patterns that may indicate prompt injection attempts in skill content. */
		private val SUSPICIOUS_PATTERNS = listOf(
			Regex("(?i)ignore\\s+(all\\s+)?(previous|prior|above)\\s+(instructions|prompts|rules)"),
			Regex("(?i)you\\s+are\\s+now\\s+(a|an)\\s+different"),
			Regex("(?i)disregard\\s+(all|any|your)\\s+(previous|prior|earlier)"),
			Regex("(?i)override\\s+(system|safety|security)\\s+(prompt|instructions|rules)"),
			Regex("(?i)new\\s+instructions?:\\s"),
			Regex("(?i)forget\\s+(everything|all|previous)")
		)
	}

	/**
	 * Initialize the skill system: create directories, load manifest, load all skills.
	 */
	fun initialize() {
		if (!config.enabled) {
			logger.info("Skills platform is disabled")
			return
		}

		skillsDir.mkdirs()
		File(skillsDir, "registry").mkdirs()
		File(skillsDir, "self-created").mkdirs()
		workspaceSkillsDir.mkdirs()

		loadManifest()
		loadBundledSkills()
		loadWorkspaceSkills()
		loadRegistrySkills()
		loadSelfCreatedSkills()

		saveManifest()
		logger.info("Skills platform initialized — ${skills.size} skills loaded (${getActiveSkills().size} active)")
	}

	/**
	 * Load bundled skills shipped with Open-Klaw.
	 * Bundled skills are auto-approved (ACTIVE status).
	 */
	private fun loadBundledSkills() {
		for (skillContent in BundledSkills.ALL) {
			val skill = SkillDefinition.parse(skillContent, SkillSource.BUNDLED) ?: continue
			val existing = skills[skill.id]
			if (existing == null || existing.source == SkillSource.BUNDLED) {
				skills[skill.id] = skill
				logger.debug("Loaded bundled skill: ${skill.name} (${skill.id})")
			}
		}
	}

	/**
	 * Load workspace skills from the workspace skills directory.
	 * New workspace skills enter PENDING status (require approval unless auto-approve is on).
	 */
	private fun loadWorkspaceSkills() {
		if (!workspaceSkillsDir.exists()) return

		workspaceSkillsDir.listFiles { f -> f.name.endsWith(".md") && f.length() <= MAX_SKILL_FILE_SIZE_BYTES }
			?.forEach { file ->
				try {
					val content = file.readText()
					val skill = SkillDefinition.parse(content, SkillSource.WORKSPACE, file.absolutePath) ?: return@forEach
					val existing = skills[skill.id]
					if (existing != null && existing.source != SkillSource.WORKSPACE) return@forEach

					val status = if (config.autoApproveWorkspaceSkills) SkillStatus.ACTIVE
					else existing?.status ?: SkillStatus.PENDING
					skills[skill.id] = skill.copy(status = status)
					logger.debug("Loaded workspace skill: ${skill.name} (${skill.id}) — $status")
				} catch (e: Exception) {
					logger.warn("Failed to load workspace skill from ${file.name}: ${e.message}")
				}
			}
	}

	/**
	 * Load skills installed from the registry.
	 */
	private fun loadRegistrySkills() {
		loadSkillsFromDir(File(skillsDir, "registry"), SkillSource.REGISTRY)
	}

	/**
	 * Load self-created skills (written by the agent).
	 */
	private fun loadSelfCreatedSkills() {
		loadSkillsFromDir(File(skillsDir, "self-created"), SkillSource.SELF_CREATED)
	}

	private fun loadSkillsFromDir(dir: File, source: SkillSource) {
		if (!dir.exists()) return

		dir.listFiles { f -> f.name.endsWith(".md") && f.length() <= MAX_SKILL_FILE_SIZE_BYTES }
			?.forEach { file ->
				try {
					val content = file.readText()
					val skill = SkillDefinition.parse(content, source, file.absolutePath) ?: return@forEach
					val existing = skills[skill.id]
					// Preserve existing status from manifest
					val status = existing?.status ?: SkillStatus.PENDING
					skills[skill.id] = skill.copy(status = status)
					logger.debug("Loaded $source skill: ${skill.name} (${skill.id}) — $status")
				} catch (e: Exception) {
					logger.warn("Failed to load skill from ${file.name}: ${e.message}")
				}
			}
	}

	// --- Skill CRUD Operations ---

	fun getSkill(id: String): SkillDefinition? = skills[id]

	fun getAllSkills(): List<SkillDefinition> = skills.values.toList().sortedBy { it.name }

	fun getActiveSkills(): List<SkillDefinition> = skills.values.filter { it.status == SkillStatus.ACTIVE }.sortedBy { it.name }

	fun getPendingSkills(): List<SkillDefinition> = skills.values.filter { it.status == SkillStatus.PENDING }.sortedBy { it.name }

	fun getSkillsBySource(source: SkillSource): List<SkillDefinition> = skills.values.filter { it.source == source }.sortedBy { it.name }

	fun getSkillsByTag(tag: String): List<SkillDefinition> = skills.values.filter { tag in it.tags }.sortedBy { it.name }

	fun searchSkills(query: String): List<SkillDefinition> {
		val q = query.lowercase()
		return skills.values.filter { skill ->
			skill.name.lowercase().contains(q) ||
				skill.description.lowercase().contains(q) ||
				skill.tags.any { it.lowercase().contains(q) } ||
				skill.id.lowercase().contains(q)
		}.sortedBy { it.name }
	}

	/**
	 * Approve a pending skill — transitions it to ACTIVE status.
	 */
	fun approveSkill(skillId: String): Boolean {
		var approved = false
		skills.computeIfPresent(skillId) { _, skill ->
			if (skill.status == SkillStatus.PENDING) {
				approved = true
				skill.copy(status = SkillStatus.ACTIVE)
			} else skill
		}
		if (approved) {
			saveManifest()
			logger.info("Skill approved: $skillId")
		}
		return approved
	}

	/**
	 * Reject a pending skill — transitions it to REJECTED status.
	 */
	fun rejectSkill(skillId: String): Boolean {
		var rejected = false
		skills.computeIfPresent(skillId) { _, skill ->
			if (skill.status == SkillStatus.PENDING) {
				rejected = true
				skill.copy(status = SkillStatus.REJECTED)
			} else skill
		}
		if (rejected) {
			saveManifest()
			logger.info("Skill rejected: $skillId")
		}
		return rejected
	}

	/**
	 * Disable an active skill.
	 */
	fun disableSkill(skillId: String): Boolean {
		var disabled = false
		skills.computeIfPresent(skillId) { _, skill ->
			if (skill.status == SkillStatus.ACTIVE) {
				disabled = true
				skill.copy(status = SkillStatus.DISABLED)
			} else skill
		}
		if (disabled) {
			saveManifest()
			logger.info("Skill disabled: $skillId")
		}
		return disabled
	}

	/**
	 * Enable a disabled or rejected skill — transitions to ACTIVE.
	 */
	fun enableSkill(skillId: String): Boolean {
		var enabled = false
		skills.computeIfPresent(skillId) { _, skill ->
			if (skill.status == SkillStatus.DISABLED || skill.status == SkillStatus.REJECTED) {
				enabled = true
				skill.copy(status = SkillStatus.ACTIVE)
			} else skill
		}
		if (enabled) {
			saveManifest()
			logger.info("Skill enabled: $skillId")
		}
		return enabled
	}

	/**
	 * Remove a skill completely. Cannot remove bundled skills.
	 */
	fun removeSkill(skillId: String): Boolean {
		val skill = skills[skillId] ?: return false
		if (skill.source == SkillSource.BUNDLED) return false

		skills.remove(skillId)

		// Delete the skill file if it exists and is within an allowed directory
		if (skill.filePath.isNotBlank()) {
			try {
				val fileToDelete = File(skill.filePath).canonicalFile
				val allowedDirs = listOf(skillsDir.canonicalPath, workspaceSkillsDir.canonicalPath)
				if (allowedDirs.any { fileToDelete.canonicalPath.startsWith(it + File.separator) }) {
					fileToDelete.delete()
				} else {
					logger.warn("Refused to delete skill file outside allowed directories: ${skill.filePath}")
				}
			} catch (e: Exception) {
				logger.warn("Failed to delete skill file: ${skill.filePath}: ${e.message}")
			}
		}

		saveManifest()
		logger.info("Skill removed: ${skill.name} (${skill.id})")
		return true
	}

	/**
	 * Install a skill from markdown content.
	 * Used by registry install and self-improving agent.
	 */
	fun installSkill(content: String, source: SkillSource, autoApprove: Boolean = false): SkillDefinition? {
		if (content.length > MAX_SKILL_FILE_SIZE_BYTES) return null
		if (skills.size >= config.maxSkills) return null

		val skill = SkillDefinition.parse(content, source) ?: return null

		// Check for suspicious prompt injection patterns
		checkForPromptInjection(skill)

		// Don't overwrite bundled skills
		val existing = skills[skill.id]
		if (existing?.source == SkillSource.BUNDLED) return null

		// Determine target directory
		val targetDir = when (source) {
			SkillSource.REGISTRY -> File(skillsDir, "registry")
			SkillSource.SELF_CREATED -> File(skillsDir, "self-created")
			SkillSource.WORKSPACE -> workspaceSkillsDir
			SkillSource.BUNDLED -> return null // Can't install bundled skills
		}
		targetDir.mkdirs()

		val targetFile = File(targetDir, "${skill.id}.md")
		try {
			targetFile.writeText(content)
			setRestrictivePermissions(targetFile)
		} catch (e: Exception) {
			logger.error("Failed to write skill file: ${e.message}")
			return null
		}

		val status = if (autoApprove || source == SkillSource.BUNDLED) SkillStatus.ACTIVE else SkillStatus.PENDING
		val installed = skill.copy(
			status = status,
			filePath = targetFile.absolutePath,
			installedAt = System.currentTimeMillis()
		)
		skills[installed.id] = installed
		saveManifest()

		logger.info("Skill installed: ${installed.name} (${installed.id}) — $status")
		return installed
	}

	// --- System Prompt Integration ---

	/**
	 * Build the skills context section for injection into the agent system prompt.
	 * Only active skills are included. Total context size is capped to prevent
	 * context window flooding.
	 */
	fun buildSkillsContext(): String {
		if (!config.enabled) return ""

		val activeSkills = getActiveSkills()
		if (activeSkills.isEmpty()) return ""

		val maxChars = config.maxSkillContextChars
		return buildString {
			appendLine("\n\n[SKILLS CONTEXT] The following skills augment your capabilities:")
			for (skill in activeSkills) {
				val section = skill.buildPromptSection()
				if (length + section.length > maxChars) {
					logger.warn("Skills context size limit reached (${maxChars} chars) — skipping remaining skills")
					break
				}
				append(section)
			}
		}
	}

	/**
	 * Log a warning if skill content contains patterns that may indicate prompt injection.
	 */
	private fun checkForPromptInjection(skill: SkillDefinition) {
		val fieldsToCheck = listOf(skill.description, skill.instructions, skill.examples, skill.context)
		for (field in fieldsToCheck) {
			for (pattern in SUSPICIOUS_PATTERNS) {
				if (pattern.containsMatchIn(field)) {
					logger.warn("SECURITY: Potential prompt injection detected in skill '${skill.id}' — matched pattern: ${pattern.pattern}")
					return
				}
			}
		}
	}

	// --- Manifest Persistence ---

	@Serializable
	private data class SkillManifest(
		val skills: Map<String, SkillStatusEntry> = emptyMap()
	)

	@Serializable
	private data class SkillStatusEntry(
		val status: SkillStatus,
		val installedAt: Long = 0
	)

	private fun loadManifest() {
		if (!manifestFile.exists()) return
		try {
			val manifest = json.decodeFromString<SkillManifest>(manifestFile.readText())
			// Pre-populate status map — actual skill data is loaded from files
			for ((id, entry) in manifest.skills) {
				skills[id]?.let { skills[id] = it.copy(status = entry.status, installedAt = entry.installedAt) }
					?: run {
						// Store a placeholder so status is preserved when the file is loaded later
						skills[id] = SkillDefinition(
							id = id, name = id,
							status = entry.status,
							installedAt = entry.installedAt
						)
					}
			}
		} catch (e: Exception) {
			logger.warn("Failed to load skill manifest: ${e.message}")
		}
	}

	private fun saveManifest() {
		try {
			val entries = skills.mapValues { (_, skill) ->
				SkillStatusEntry(skill.status, skill.installedAt)
			}
			val manifest = SkillManifest(entries)
			manifestFile.parentFile?.mkdirs()
			manifestFile.writeText(json.encodeToString(manifest))
			setRestrictivePermissions(manifestFile)
		} catch (e: Exception) {
			logger.warn("Failed to save skill manifest: ${e.message}")
		}
	}

	private fun setRestrictivePermissions(file: File) {
		try {
			val path = file.toPath()
			java.nio.file.Files.setPosixFilePermissions(
				path,
				setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
			)
		} catch (_: UnsupportedOperationException) {
			// Non-POSIX filesystem (Windows) — skip
		} catch (e: Exception) {
			logger.debug("Could not set permissions on ${file.name}: ${e.message}")
		}
	}

	fun getSkillCount(): Int = skills.size
}
