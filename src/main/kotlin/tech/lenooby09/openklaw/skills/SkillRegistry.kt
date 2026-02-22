package tech.lenooby09.openklaw.skills

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.SkillsConfig
import java.net.URI

/**
 * Client for the community Skill Registry (ClawHub equivalent).
 *
 * Provides search, install, and publish operations against a remote registry API.
 * When the registry is unavailable or unconfigured, operations return empty/error results
 * gracefully — the system never blocks on registry connectivity.
 *
 * Security:
 * - Only HTTPS URLs are accepted (enforced in [validateRegistryUrl]).
 * - Registry host must be in the configured allowlist ([SkillsConfig.registryAllowedHosts]).
 * - Strict timeouts and response size limits should be set on the HTTP client when implemented.
 *
 * Registry API contract:
 * - GET  /skills?q={query}           → search skills
 * - GET  /skills/{id}                → get skill detail (markdown content)
 * - POST /skills                     → publish a skill
 * - GET  /skills/{id}/versions       → list versions
 */
class SkillRegistryClient(
	private val config: SkillsConfig,
	private val skillManager: SkillManager
) {
	private val logger = LoggerFactory.getLogger(SkillRegistryClient::class.java)
	private val registryValidated: Boolean = validateRegistryUrl()

	@Serializable
	data class RegistryEntry(
		val id: String,
		val name: String,
		val version: String = "1.0.0",
		val author: String = "",
		val description: String = "",
		val tags: List<String> = emptyList(),
		val downloads: Int = 0,
		val rating: Double = 0.0
	)

	@Serializable
	data class SearchResult(
		val skills: List<RegistryEntry> = emptyList(),
		val total: Int = 0,
		val page: Int = 1
	)

	@Serializable
	data class PublishResult(
		val success: Boolean,
		val message: String = "",
		val id: String = ""
	)

	/**
	 * Validate the configured registry URL at startup.
	 * Enforces HTTPS scheme and host allowlist to prevent SSRF.
	 */
	private fun validateRegistryUrl(): Boolean {
		if (config.registryUrl.isBlank()) return false
		return try {
			val uri = URI(config.registryUrl)
			if (!uri.scheme.equals("https", ignoreCase = true)) {
				logger.warn("SECURITY: Registry URL must use HTTPS — got '${uri.scheme}'. Registry operations disabled.")
				return false
			}
			val host = uri.host?.lowercase() ?: run {
				logger.warn("SECURITY: Registry URL has no valid host. Registry operations disabled.")
				return false
			}
			if (config.registryAllowedHosts.isNotEmpty() && config.registryAllowedHosts.none { it.lowercase() == host }) {
				logger.warn("SECURITY: Registry host '$host' is not in the allowed hosts list: ${config.registryAllowedHosts}. Registry operations disabled.")
				return false
			}
			true
		} catch (e: Exception) {
			logger.warn("SECURITY: Invalid registry URL '${config.registryUrl}': ${e.message}. Registry operations disabled.")
			false
		}
	}

	/**
	 * Search the community registry for skills matching a query.
	 * Returns a list of registry entries (metadata only, not full content).
	 */
	fun search(query: String, page: Int = 1, limit: Int = 20): SearchResult {
		if (!registryValidated) {
			return SearchResult()
		}

		// HTTP call to registry API
		// In production this would use Ktor HttpClient; for now we return a stub
		// that indicates the registry is available but yielded no results.
		// This allows the full install pipeline to work once a registry is deployed.
		logger.debug("Registry search: query=$query page=$page limit=$limit url=${config.registryUrl}")

		return SearchResult(skills = emptyList(), total = 0, page = page)
	}

	/**
	 * Fetch the full skill content (SKILL.md) from the registry by ID.
	 * Returns the raw markdown string, or null if not found.
	 */
	fun fetchSkillContent(skillId: String): String? {
		if (!registryValidated) return null
		if (!SkillDefinition.isValidId(skillId)) return null

		logger.debug("Registry fetch: skillId=$skillId url=${config.registryUrl}")

		// Stub — returns null until a live registry is available
		return null
	}

	/**
	 * Install a skill from the registry by its ID.
	 * Fetches content from the registry and delegates to SkillManager.installSkill().
	 */
	fun install(skillId: String): SkillDefinition? {
		val content = fetchSkillContent(skillId)
		if (content == null) {
			logger.warn("Skill '$skillId' not found in registry")
			return null
		}

		return skillManager.installSkill(content, SkillSource.REGISTRY)
	}

	/**
	 * Publish a local skill to the community registry.
	 * Only active, non-bundled skills can be published.
	 */
	fun publish(skillId: String): PublishResult {
		if (!registryValidated) {
			return PublishResult(success = false, message = "Registry not available or URL validation failed")
		}

		val skill = skillManager.getSkill(skillId)
			?: return PublishResult(success = false, message = "Skill '$skillId' not found")

		if (skill.source == SkillSource.BUNDLED) {
			return PublishResult(success = false, message = "Cannot publish bundled skills")
		}

		if (skill.status != SkillStatus.ACTIVE) {
			return PublishResult(success = false, message = "Only active skills can be published")
		}

		logger.debug("Registry publish: skillId=$skillId url=${config.registryUrl}")

		// Stub — returns success=false until a live registry is available
		return PublishResult(success = false, message = "Registry not yet available. Skill is ready for publishing when the registry comes online.", id = skillId)
	}

	/**
	 * Check if the registry is reachable.
	 */
	fun isAvailable(): Boolean {
		if (!registryValidated) return false
		// Stub — would ping the registry health endpoint
		return false
	}
}
