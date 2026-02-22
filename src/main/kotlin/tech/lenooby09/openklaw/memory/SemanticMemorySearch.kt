package tech.lenooby09.openklaw.memory

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import tech.lenooby09.openklaw.config.MemoryConfig
import kotlin.math.sqrt

/**
 * Vector-based semantic search over conversation logs using TF-IDF.
 * Provides retrieval of relevant past conversations for each agent turn,
 * enabling context-aware responses grounded in historical interactions.
 *
 * No external vector DB required — all indexing and search happens in-memory
 * with on-disk JSONL logs as the backing store.
 */
class SemanticMemorySearch(
	private val config: MemoryConfig,
	private val conversationLogger: ConversationLogger
) {
	private val logger = LoggerFactory.getLogger(SemanticMemorySearch::class.java)

	private data class IndexedDocument(
		val content: String,
		val metadata: JsonObject,
		val tfidfVector: Map<String, Double>
	)

	private var index: List<IndexedDocument> = emptyList()
	private var idf: Map<String, Double> = emptyMap()
	private var lastIndexTime: Long = 0

	companion object {
		private const val REINDEX_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
		private val STOP_WORDS = setOf(
			"the", "a", "an", "is", "it", "in", "on", "at", "to", "for", "of", "and", "or", "but",
			"not", "with", "this", "that", "from", "by", "as", "be", "was", "are", "were", "been",
			"has", "have", "had", "do", "does", "did", "will", "would", "could", "should", "may",
			"might", "can", "i", "you", "he", "she", "we", "they", "my", "your", "his", "her",
			"its", "our", "their", "me", "him", "us", "them", "what", "which", "who", "when",
			"where", "how", "if", "then", "so", "no", "yes", "just", "also", "very", "too"
		)
	}

	/**
	 * Search for the most relevant past conversation entries given a query.
	 * Returns a list of (content, score, metadata) triples sorted by relevance.
	 */
	fun search(query: String, maxResults: Int = config.semanticSearchMaxResults): List<SearchResult> {
		if (!config.semanticSearchEnabled) return emptyList()

		try {
			ensureIndex()

			if (index.isEmpty()) return emptyList()

			val queryTokens = tokenize(query)
			val queryTf = computeTf(queryTokens)
			val queryTfidf = queryTf.mapValues { (term, tf) -> tf * (idf[term] ?: 0.0) }

			return index
				.map { doc -> SearchResult(doc.content, cosineSimilarity(queryTfidf, doc.tfidfVector), doc.metadata) }
				.filter { it.score >= config.semanticSearchMinScore }
				.sortedByDescending { it.score }
				.take(maxResults)
		} catch (e: Exception) {
			logger.error("Semantic search failed: ${e.message}", e)
			return emptyList()
		}
	}

	/**
	 * Forces a reindex of all conversation logs.
	 */
	fun reindex() {
		try {
			val rawLogs = conversationLogger.readAllLogs()
			// Cap the number of indexed documents to prevent unbounded memory growth
			val allLogs = if (rawLogs.size > config.semanticSearchMaxDocuments) {
				rawLogs.takeLast(config.semanticSearchMaxDocuments)
			} else rawLogs

			if (allLogs.isEmpty()) {
				index = emptyList()
				idf = emptyMap()
				lastIndexTime = System.currentTimeMillis()
				return
			}

			// Compute document frequencies
			val docFreq = mutableMapOf<String, Int>()
			val docTokens = allLogs.map { (content, _) ->
				val tokens = tokenize(content)
				tokens.toSet().forEach { token -> docFreq[token] = (docFreq[token] ?: 0) + 1 }
				tokens
			}

			val n = allLogs.size.toDouble()
			idf = docFreq.mapValues { (_, df) -> Math.log(n / df) }

			index = allLogs.zip(docTokens).map { (pair, tokens) ->
				val (content, metadata) = pair
				val tf = computeTf(tokens)
				val tfidf = tf.mapValues { (term, tfVal) -> tfVal * (idf[term] ?: 0.0) }
				IndexedDocument(content, metadata, tfidf)
			}

			lastIndexTime = System.currentTimeMillis()
			logger.debug("Semantic search index rebuilt with ${index.size} documents")
		} catch (e: Exception) {
			logger.error("Failed to build semantic search index: ${e.message}", e)
		}
	}

	private fun ensureIndex() {
		val now = System.currentTimeMillis()
		if (index.isEmpty() || now - lastIndexTime > REINDEX_INTERVAL_MS) {
			reindex()
		}
	}

	internal fun tokenize(text: String): List<String> {
		return text.lowercase()
			.replace(Regex("[^a-z0-9\\s]"), " ")
			.split(Regex("\\s+"))
			.filter { it.length > 1 && it !in STOP_WORDS }
	}

	private fun computeTf(tokens: List<String>): Map<String, Double> {
		if (tokens.isEmpty()) return emptyMap()
		val counts = mutableMapOf<String, Int>()
		tokens.forEach { counts[it] = (counts[it] ?: 0) + 1 }
		val max = counts.values.max().toDouble()
		return counts.mapValues { (_, count) -> count / max }
	}

	private fun cosineSimilarity(a: Map<String, Double>, b: Map<String, Double>): Double {
		val allTerms = a.keys.intersect(b.keys)
		if (allTerms.isEmpty()) return 0.0

		var dotProduct = 0.0
		var normA = 0.0
		var normB = 0.0

		for (term in allTerms) {
			val aVal = a[term] ?: 0.0
			val bVal = b[term] ?: 0.0
			dotProduct += aVal * bVal
		}
		for ((_, v) in a) normA += v * v
		for ((_, v) in b) normB += v * v

		val denominator = sqrt(normA) * sqrt(normB)
		return if (denominator == 0.0) 0.0 else dotProduct / denominator
	}

	data class SearchResult(
		val content: String,
		val score: Double,
		val metadata: JsonObject
	) {
		val role: String get() = metadata["role"]?.jsonPrimitive?.content ?: "unknown"
		val timestamp: Long get() = metadata["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L
		val sessionId: String get() = metadata["sessionId"]?.jsonPrimitive?.content ?: ""
	}
}
