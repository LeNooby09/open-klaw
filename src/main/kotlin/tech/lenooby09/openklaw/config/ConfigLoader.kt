package tech.lenooby09.openklaw.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import org.slf4j.LoggerFactory
import java.io.File

object ConfigLoader {
	private val logger = LoggerFactory.getLogger(ConfigLoader::class.java)
	private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

	fun load(path: String = "config.yaml"): AppConfig {
		val file = File(path)
		if (!file.exists() || file.readText().isBlank()) {
			logger.info("No config file found at '${file.absolutePath}' — generating default config")
			val defaultConfig = AppConfig()
			file.writeText(serializeToYaml(defaultConfig))
			logger.info("Default configuration written to '${file.absolutePath}'")
			return defaultConfig
		}
		return try {
			val text = file.readText()
			val config = yaml.decodeFromString(AppConfig.serializer(), text)
			logger.info("Configuration loaded from '${file.absolutePath}'")
			// Re-persist the full config so partial/override-only files are expanded
			// to include all fields with their effective values
			file.writeText(serializeToYaml(config))
			logger.info("Full configuration persisted back to '${file.absolutePath}'")
			config
		} catch (e: Exception) {
			logger.error("Failed to parse config file '${file.absolutePath}': ${e.message}")
			throw IllegalStateException("Invalid configuration file '${file.absolutePath}': ${e.message}", e)
		}
	}

	/**
	 * Serializes an [AppConfig] to a YAML string with a header comment.
	 */
	fun serializeToYaml(config: AppConfig): String {
		val header = "# Open-Klaw Configuration\n# Edit values below — the full configuration is shown.\n\n"
		return header + yaml.encodeToString(AppConfig.serializer(), config)
	}
}
