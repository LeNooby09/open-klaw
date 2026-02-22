package tech.lenooby09.openklaw.scheduler

import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.SchedulerConfig
import tech.lenooby09.openklaw.messaging.ChannelRouter
import tech.lenooby09.openklaw.messaging.ChannelType
import tech.lenooby09.openklaw.messaging.OutboundMessage
import tech.lenooby09.openklaw.session.SessionManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Proactive notification service that alerts users via their preferred messaging channel
 * when the agent detects something that needs attention.
 *
 * Sources of notifications include:
 * - Heartbeat tasks that produce alerts
 * - Cron job results that need user attention
 * - Git/CI build failures
 * - Webhook-triggered events
 *
 * Each user can configure a preferred notification channel. If not set, falls back to
 * the default configured channel.
 */
class NotificationService(
	private val config: SchedulerConfig,
	private val channelRouter: ChannelRouter,
	private val sessionManager: SessionManager
) {
	private val logger = LoggerFactory.getLogger(NotificationService::class.java)

	/** Per-user preferred notification channel type. */
	private val userPreferences = ConcurrentHashMap<String, ChannelType>()

	/** Thread-safe bounded notification history using synchronized ArrayList. */
	private val history = ArrayList<Notification>()
	private val historyLock = ReentrantLock()

	companion object {
		private const val MAX_HISTORY = 1000
	}

	fun setUserPreference(username: String, channelType: ChannelType) {
		userPreferences[username] = channelType
		logger.info("Notification preference set for $username: $channelType")
	}

	fun getUserPreference(username: String): ChannelType? = userPreferences[username]

	fun clearUserPreference(username: String) {
		userPreferences.remove(username)
	}

	/**
	 * Send a notification to a specific user via their preferred channel.
	 * Falls back to default channel if no preference is set.
	 */
	suspend fun notify(username: String, title: String, message: String, priority: NotificationPriority = NotificationPriority.NORMAL): Boolean {
		if (!config.notificationsEnabled) {
			logger.debug("Notifications disabled, skipping: $title for $username")
			return false
		}

		val channelType = resolveChannel(username)
		val formattedMessage = formatNotification(title, message, priority)

		val notification = Notification(
			username = username,
			title = title,
			message = message,
			priority = priority,
			channelType = channelType,
			timestamp = System.currentTimeMillis()
		)

		val sent = sendToChannel(username, channelType, formattedMessage)
		notification.delivered = sent

		addToHistory(notification)

		if (sent) {
			logger.info("Notification sent to $username via $channelType: $title")
		} else {
			logger.warn("Failed to send notification to $username via $channelType: $title")
		}

		return sent
	}

	/**
	 * Broadcast a notification to all registered users.
	 */
	suspend fun broadcast(title: String, message: String, priority: NotificationPriority = NotificationPriority.NORMAL): Int {
		if (!config.notificationsEnabled) return 0

		val users = sessionManager.listUsers()
		var sentCount = 0
		for (user in users) {
			if (notify(user.username, title, message, priority)) {
				sentCount++
			}
		}
		logger.info("Broadcast notification sent to $sentCount/${users.size} users: $title")
		return sentCount
	}

	fun getHistory(username: String? = null, limit: Int = 50): List<Notification> {
		historyLock.withLock {
			val filtered = if (username != null) {
				history.filter { it.username == username }
			} else {
				history.toList()
			}
			return filtered.sortedByDescending { it.timestamp }.take(limit)
		}
	}

	fun getHistoryCount(): Int = historyLock.withLock { history.size }

	private fun resolveChannel(username: String): ChannelType {
		// 1. User preference
		userPreferences[username]?.let { return it }

		// 2. Find a linked channel for this user
		val links = sessionManager.getChannelLinks(username)
		if (links.isNotEmpty()) {
			val linked = links.firstOrNull { link ->
				try {
					val type = ChannelType.valueOf(link.channelType)
					channelRouter.getChannel(type)?.connected == true
				} catch (e: Exception) {
					false
				}
			}
			if (linked != null) {
				return try {
					ChannelType.valueOf(linked.channelType)
				} catch (e: Exception) {
					getDefaultChannel()
				}
			}
		}

		// 3. Default channel
		return getDefaultChannel()
	}

	private fun getDefaultChannel(): ChannelType {
		return try {
			ChannelType.valueOf(config.defaultNotificationChannel)
		} catch (e: Exception) {
			ChannelType.WEBCHAT
		}
	}

	private suspend fun sendToChannel(username: String, channelType: ChannelType, message: String): Boolean {
		val channel = channelRouter.getChannel(channelType)
		if (channel == null || !channel.connected) {
			logger.warn("Channel $channelType not available for notification to $username")
			return false
		}

		// Resolve channel user ID from account links
		val channelUserId = resolveChannelUserId(username, channelType) ?: run {
			logger.warn("No linked $channelType identity for user $username")
			return false
		}

		return try {
			channel.sendMessage(
				OutboundMessage(
					channelType = channelType,
					channelId = channelUserId,
					recipientId = channelUserId,
					content = message
				)
			)
		} catch (e: Exception) {
			logger.error("Error sending notification to $username via $channelType: ${e.message}", e)
			false
		}
	}

	private fun resolveChannelUserId(username: String, channelType: ChannelType): String? {
		val links = sessionManager.getChannelLinks(username)
		return links.firstOrNull { it.channelType == channelType.name }?.channelUserId
	}

	private fun formatNotification(title: String, message: String, priority: NotificationPriority): String {
		val icon = when (priority) {
			NotificationPriority.LOW -> "ℹ️"
			NotificationPriority.NORMAL -> "🔔"
			NotificationPriority.HIGH -> "⚠️"
			NotificationPriority.CRITICAL -> "🚨"
		}
		return "$icon **$title**\n\n$message"
	}

	/**
	 * Add notification to bounded history with atomic trim under lock.
	 */
	private fun addToHistory(notification: Notification) {
		historyLock.withLock {
			history.add(notification)
			while (history.size > MAX_HISTORY) {
				history.removeAt(0)
			}
		}
	}
}

data class Notification(
	val username: String,
	val title: String,
	val message: String,
	val priority: NotificationPriority,
	val channelType: ChannelType,
	val timestamp: Long,
	var delivered: Boolean = false
)

enum class NotificationPriority {
	LOW, NORMAL, HIGH, CRITICAL
}
