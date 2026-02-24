package tech.lenooby09.openklaw.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe holder for the application configuration that supports hot-reloading.
 * Components can read the current config via [current] and register listeners via [onChange]
 * to be notified when the config is updated at runtime.
 */
class ConfigHolder(initial: AppConfig, private val configPath: String = "config.yaml") {

	private val logger = LoggerFactory.getLogger(ConfigHolder::class.java)
	private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
	private val listeners = CopyOnWriteArrayList<(AppConfig) -> Unit>()

	@Volatile
	var current: AppConfig = initial
		private set

	/**
	 * Register a callback that is invoked whenever the config changes.
	 * The callback receives the new [AppConfig].
	 */
	fun onChange(listener: (AppConfig) -> Unit) {
		listeners.add(listener)
	}

	/**
	 * Atomically update the configuration, persist it to disk as YAML, and notify listeners.
	 * Returns the new config on success or throws on serialization/write failure.
	 */
	@Synchronized
	fun update(newConfig: AppConfig) {
		val yamlText = yaml.encodeToString(AppConfig.serializer(), newConfig)
		val file = File(configPath)
		file.writeText(yamlText)
		logger.info("Configuration persisted to '${file.absolutePath}'")

		current = newConfig
		notifyListeners(newConfig)
		logger.info("Configuration hot-reloaded successfully")
	}

	/**
	 * Reload the configuration from disk and notify listeners.
	 * Returns the reloaded config or throws on parse failure.
	 */
	@Synchronized
	fun reload(): AppConfig {
		val file = File(configPath)
		if (!file.exists()) {
			logger.warn("Config file not found at '${file.absolutePath}' — keeping current config")
			return current
		}
		val text = file.readText()
		val reloaded = yaml.decodeFromString(AppConfig.serializer(), text)
		current = reloaded
		notifyListeners(reloaded)
		logger.info("Configuration reloaded from '${file.absolutePath}'")
		return reloaded
	}

	private fun notifyListeners(config: AppConfig) {
		for (listener in listeners) {
			try {
				listener(config)
			} catch (e: Exception) {
				logger.error("Config change listener failed: ${e.message}", e)
			}
		}
	}
}
