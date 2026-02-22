package tech.lenooby09.openklaw.messaging

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.EmailConfig
import java.util.Properties
import java.util.concurrent.ConcurrentLinkedDeque
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.FlagTerm

/**
 * Email messaging integration via IMAP (receive) and SMTP (send).
 *
 * Polls an IMAP inbox for new messages and sends replies via SMTP.
 * Supports Gmail (with App Passwords), Outlook, and any standard IMAP/SMTP provider.
 */
class EmailChannel(
	private val config: EmailConfig,
	private val dedupMaxSize: Int = 10_000
) : MessageChannel {

	private val logger = LoggerFactory.getLogger(EmailChannel::class.java)

	override val channelType = ChannelType.EMAIL
	override val displayName = "Email"
	override var connected: Boolean = false
		private set

	private var messageHandler: (suspend (InboundMessage) -> Unit)? = null
	private var pollingJob: Job? = null
	private var imapStore: Store? = null

	/** LRU dedup deque — newest at tail, oldest at head. */
	private val processedMessageIds = ConcurrentLinkedDeque<String>()
	private val processedSet = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
	private val dedupLock = Any()

	override suspend fun start() {
		if (config.imapHost.isEmpty() || config.username.isEmpty() || config.password.isEmpty()) {
			logger.warn("Email credentials not configured, skipping Email channel")
			return
		}

		try {
			val props = Properties().apply {
				put("mail.imap.host", config.imapHost)
				put("mail.imap.port", config.imapPort.toString())
				put("mail.imap.ssl.enable", config.imapSsl.toString())
				put("mail.imap.starttls.enable", config.imapStartTls.toString())
				put("mail.imap.connectiontimeout", "10000")
				put("mail.imap.timeout", "10000")
			}

			val session = Session.getInstance(props)
			val store = session.getStore("imap")
			store.connect(config.imapHost, config.imapPort, config.username, config.password)
			imapStore = store
			connected = true
			logger.info("Email channel connected to ${config.imapHost} as ${config.username}")
		} catch (e: Exception) {
			logger.error("Failed to connect to email server: ${e.message}", e)
			return
		}

		// Start polling for new messages
		pollingJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
			pollInbox()
		}
	}

	override suspend fun stop() {
		pollingJob?.cancel()
		pollingJob = null
		try {
			imapStore?.close()
		} catch (e: Exception) {
			logger.debug("Error closing IMAP store: ${e.message}")
		}
		imapStore = null
		connected = false
		synchronized(dedupLock) {
			processedMessageIds.clear()
			processedSet.clear()
		}
		logger.info("Email channel stopped")
	}

	override suspend fun sendMessage(message: OutboundMessage): Boolean {
		if (!connected) return false

		return try {
			val props = Properties().apply {
				put("mail.smtp.host", config.smtpHost)
				put("mail.smtp.port", config.smtpPort.toString())
				put("mail.smtp.auth", "true")
				put("mail.smtp.ssl.enable", config.smtpSsl.toString())
				put("mail.smtp.starttls.enable", config.smtpStartTls.toString())
				put("mail.smtp.connectiontimeout", "10000")
				put("mail.smtp.timeout", "10000")
			}

			val session = Session.getInstance(props, object : Authenticator() {
				override fun getPasswordAuthentication(): PasswordAuthentication {
					return PasswordAuthentication(config.username, config.password)
				}
			})

			val mimeMessage = MimeMessage(session).apply {
				setFrom(InternetAddress(config.username, config.fromName))
				setRecipient(Message.RecipientType.TO, InternetAddress(message.recipientId))
				subject = message.metadata["subject"] ?: "Re: Your message"
				setText(message.content, "UTF-8")

				// Set In-Reply-To header for threading
				message.metadata["inReplyTo"]?.let {
					setHeader("In-Reply-To", it)
					setHeader("References", it)
				}
			}

			withContext(Dispatchers.IO) {
				Transport.send(mimeMessage)
			}
			logger.debug("Email sent to ${message.recipientId}")
			true
		} catch (e: Exception) {
			logger.error("Error sending email: ${e.message}", e)
			false
		}
	}

	override fun onMessage(handler: suspend (InboundMessage) -> Unit) {
		messageHandler = handler
	}

	/**
	 * Triggers an immediate inbox check. Useful for Gmail Pub/Sub webhook callbacks.
	 */
	suspend fun checkInboxNow() {
		if (!connected) return
		fetchNewMessages()
	}

	private suspend fun pollInbox() {
		while (coroutineContext.isActive) {
			try {
				fetchNewMessages()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				logger.error("Email polling error: ${e.message}", e)
			}
			delay(config.pollIntervalMs)
		}
	}

	private suspend fun fetchNewMessages() {
		val store = imapStore ?: return

		try {
			// Reconnect if needed
			if (!store.isConnected) {
				store.connect(config.imapHost, config.imapPort, config.username, config.password)
			}

			val folder = store.getFolder(config.inboxFolder)
			folder.open(Folder.READ_WRITE)

			try {
				// Fetch only unseen messages
				val unseen = folder.search(FlagTerm(Flags(Flags.Flag.SEEN), false))

				for (msg in unseen) {
					val messageId = msg.getHeader("Message-ID")?.firstOrNull() ?: continue

					// LRU deduplication
					if (!addToDedup(messageId)) continue

					val from = (msg.from?.firstOrNull() as? InternetAddress)
					val senderEmail = from?.address ?: continue
					val senderName = from.personal ?: senderEmail

					// Check allowlist if configured
					if (config.allowedSenders.isNotEmpty() &&
						senderEmail !in config.allowedSenders) {
						logger.debug("Ignoring email from non-allowed sender: $senderEmail")
						continue
					}

					val subject = msg.subject ?: "(no subject)"
					val body = extractTextContent(msg)

					if (body.isBlank()) continue

					// Mark as seen
					msg.setFlag(Flags.Flag.SEEN, true)

					val inbound = InboundMessage(
						channelType = ChannelType.EMAIL,
						channelId = senderEmail,
						senderId = senderEmail,
						senderName = senderName,
						content = "$subject\n\n$body",
						metadata = mapOf(
							"messageId" to messageId,
							"subject" to subject
						)
					)

					try {
						messageHandler?.invoke(inbound)
					} catch (e: Exception) {
						logger.error("Error handling email message: ${e.message}", e)
					}
				}
			} finally {
				folder.close(false)
			}
		} catch (e: Exception) {
			logger.error("Error fetching emails: ${e.message}", e)
			// Try to reconnect on next poll
			try { store.close() } catch (_: Exception) {}
			try {
				store.connect(config.imapHost, config.imapPort, config.username, config.password)
			} catch (_: Exception) {}
		}
	}

	/**
	 * Extracts plain text content from a MIME message, handling multipart structures.
	 */
	private fun extractTextContent(message: Message): String {
		return when (val content = message.content) {
			is String -> content.take(config.maxEmailBodyLength)
			is Multipart -> extractFromMultipart(content, maxDepth = MAX_MULTIPART_DEPTH).take(config.maxEmailBodyLength)
			else -> ""
		}
	}

	/**
	 * Recursively extracts text from multipart MIME structures with depth limit
	 * to prevent stack overflow from maliciously crafted emails.
	 */
	private fun extractFromMultipart(multipart: Multipart, maxDepth: Int): String {
		if (maxDepth <= 0) return ""

		val parts = mutableListOf<String>()
		for (i in 0 until multipart.count) {
			val part = multipart.getBodyPart(i)
			when {
				part.isMimeType("text/plain") -> {
					parts.add(part.content.toString())
				}
				part.content is Multipart -> {
					parts.add(extractFromMultipart(part.content as Multipart, maxDepth - 1))
				}
			}
		}
		// Prefer plain text; fall back to HTML stripping if no plain text found
		if (parts.isEmpty()) {
			for (i in 0 until multipart.count) {
				val part = multipart.getBodyPart(i)
				if (part.isMimeType("text/html")) {
					parts.add(stripHtml(part.content.toString()))
				}
			}
		}
		return parts.joinToString("\n")
	}

	private fun stripHtml(html: String): String {
		return html
			.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
			.replace(Regex("<p\\s*>", RegexOption.IGNORE_CASE), "\n")
			.replace(Regex("<[^>]+>"), "")
			.replace(Regex("&nbsp;"), " ")
			.replace(Regex("&amp;"), "&")
			.replace(Regex("&lt;"), "<")
			.replace(Regex("&gt;"), ">")
			.trim()
	}

	/**
	 * Adds a message ID to the LRU dedup set. Returns true if newly added, false if duplicate.
	 */
	private fun addToDedup(messageId: String): Boolean {
		synchronized(dedupLock) {
			if (!processedSet.add(messageId)) return false
			processedMessageIds.addLast(messageId)
			while (processedMessageIds.size > dedupMaxSize) {
				val evicted = processedMessageIds.pollFirst() ?: break
				processedSet.remove(evicted)
			}
			return true
		}
	}

	companion object {
		private const val MAX_MULTIPART_DEPTH = 10
	}
}
