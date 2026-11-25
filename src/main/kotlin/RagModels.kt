package dev.notnull

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.HexFormat

data class RagPage(
    val game: String,
    val pageId: Long,
    val title: String,
    val url: String,
    val content: String,
    val sourceHash: String,
    val fetchedAt: Long = System.currentTimeMillis(),
)

data class RagChunk(
    val id: String,
    val pageId: Long,
    val game: String,
    val title: String,
    val section: String,
    val url: String,
    val content: String,
    val embeddingText: String,
    val spoiler: Boolean,
    val metadata: String,
)

data class KnowledgeHit(
    val id: String,
    val pageId: String,
    val game: String,
    val title: String,
    val section: String,
    val url: String,
    val content: String,
    val spoiler: Boolean,
    val metadata: String,
    val score: Double,
)

data class RagSource(
    val index: Int,
    val game: String,
    val title: String,
    val section: String,
    val url: String,
)

data class RagContext(
    val prompt: String,
    val sources: List<RagSource>,
    val hasSpoiler: Boolean,
    val fromWeb: Boolean,
) {
    fun sourcesText(): String =
        sources.joinToString("\n") { source ->
            val section = if (source.section.isBlank()) "" else " > ${source.section}"
            "[${source.index}] ${source.game}: ${source.title}$section — ${source.url}"
        }
}

object Hashing {
    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }
}

object EmbeddingText {
    fun query(text: String): String = "task: search result | query: ${text.trim()}"

    fun document(
        title: String,
        section: String,
        text: String,
    ): String {
        val fullTitle = if (section.isBlank()) title else "$title > $section"
        return "title: $fullTitle | text: ${text.trim()}"
    }
}

object GameDetector {
    private val darkSouls3 = Regex("\\b(dark souls\\s*(3|iii)|ds3)\\b", RegexOption.IGNORE_CASE)
    private val darkSouls2 = Regex("\\b(dark souls\\s*(2|ii)|ds2)\\b", RegexOption.IGNORE_CASE)
    private val darkSouls1 = Regex("\\b(dark souls\\s*([1i])|ds1)\\b", RegexOption.IGNORE_CASE)
    private val eldenRing =
        Regex(
            "\\b(elden ring|elden|tarnished|lands between|maliketh|malenia|radahn|godrick|rennala|morgott|fire giant|placidusax|ranni|blaidd|twin maidens)\\b",
            RegexOption.IGNORE_CASE,
        )
    private val helldivers =
        Regex(
            "\\b(helldivers?\\s*2|helldivers?|hd2|super earth|terminid[sz]?|automatons?|illuminate[sd]?|stratagems?|stratagema[s]?|warbonds?|major order|democracy officer|pelican-1|super destroyer)\\b",
            RegexOption.IGNORE_CASE,
        )

    fun detect(text: String): String? =
        when {
            darkSouls3.containsMatchIn(text) -> "darksouls3"
            darkSouls2.containsMatchIn(text) -> "darksouls2"
            darkSouls1.containsMatchIn(text) -> "darksouls1"
            eldenRing.containsMatchIn(text) -> "eldenring"
            helldivers.containsMatchIn(text) -> "helldivers2"
            else -> null
        }

    fun label(game: String?): String =
        when (game) {
            "helldivers2" -> "Helldivers 2"
            "darksouls1" -> "Dark Souls"
            "darksouls2" -> "Dark Souls II"
            "darksouls3" -> "Dark Souls III"
            "eldenring" -> "Elden Ring"
            else -> "Base de conhecimento"
        }
}

object SpoilerDetector {
    private val spoilerTerms =
        listOf(
            "boss",
            "chefe",
            "ending",
            "final",
            "lore",
            "secret",
            "segredo",
            "hidden",
            "true ending",
            "questline",
            "npc",
            "story",
            "história",
        )

    fun isSpoiler(
        game: String,
        title: String,
        section: String,
    ): Boolean {
        if (game == "helldivers2") return false
        val heading = "$title $section".lowercase()
        if (spoilerTerms.any { heading.contains(it) }) return true
        return false
    }
}

object Chunker {
    private const val DEFAULT_MAX_CHARS = 1200
    private const val DEFAULT_OVERLAP = 160

    private data class Section(
        val heading: String,
        val body: String,
    )

    fun chunkPage(
        page: RagPage,
        maxChars: Int = DEFAULT_MAX_CHARS,
        overlap: Int = DEFAULT_OVERLAP,
    ): List<RagChunk> {
        val chunks = mutableListOf<RagChunk>()
        var index = 0

        for (section in splitSections(page.content)) {
            for (part in splitBody(section.body, maxChars, overlap)) {
                val content = part.trim()
                if (content.length < 80 || content.count { it.isLetterOrDigit() } < 40) continue

                val id = Hashing.sha256("${page.game}|${page.pageId}|${section.heading}|$index|$content")
                val metadata =
                    buildJsonObject {
                        put("pageId", page.pageId)
                        put("source", "fandom")
                        put("lang", "en")
                        put("section", section.heading)
                        put("chunkIndex", index)
                    }.toString()

                chunks.add(
                    RagChunk(
                        id = id,
                        pageId = page.pageId,
                        game = page.game,
                        title = page.title,
                        section = section.heading,
                        url = page.url,
                        content = content,
                        embeddingText = EmbeddingText.document(page.title, section.heading, content),
                        spoiler = SpoilerDetector.isSpoiler(page.game, page.title, section.heading),
                        metadata = metadata,
                    ),
                )
                index++
            }
        }
        return chunks
    }

    private fun splitSections(content: String): List<Section> {
        val sections = mutableListOf<Section>()
        var heading = "Introdução"
        val body = StringBuilder()

        fun flush() {
            val text = body.toString().trim()
            if (text.isNotEmpty()) sections.add(Section(heading, text))
            body.clear()
        }

        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("## ")) {
                flush()
                heading = trimmed.removePrefix("## ").trim()
            } else {
                body.appendLine(trimmed)
            }
        }
        flush()
        return if (sections.isEmpty()) listOf(Section("Introdução", content)) else sections
    }

    private fun splitBody(
        body: String,
        maxChars: Int,
        overlap: Int,
    ): List<String> {
        val paragraphs =
            body
                .split(Regex("\\n\\s*\\n"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val text = current.toString().trim()
            if (text.isNotEmpty()) chunks.add(text)
            current.clear()
        }

        for (paragraph in paragraphs) {
            for (piece in splitLong(paragraph, maxChars)) {
                if (current.isEmpty()) {
                    current.append(piece)
                } else if (current.length + piece.length + 2 <= maxChars) {
                    current.append("\n\n").append(piece)
                } else {
                    val tail = sentenceTail(current.toString(), overlap)
                    flush()
                    if (tail.isNotEmpty()) current.append(tail).append("\n\n")
                    current.append(piece)
                }
            }
        }
        flush()
        return chunks
    }

    private fun sentenceTail(
        text: String,
        overlap: Int,
    ): String {
        if (text.length <= overlap) return text.trim()
        val start = text.length - overlap
        val boundary = text.indexOf(". ", start)
        return if (boundary in start until text.length - 40) {
            text.substring(boundary + 2).trim()
        } else {
            text.substring(start).trim()
        }
    }

    private fun splitLong(
        text: String,
        maxChars: Int,
    ): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            if (current.isNotEmpty() && current.length + sentence.length + 1 > maxChars) {
                parts.add(current.toString().trim())
                current.clear()
            }
            if (sentence.length > maxChars) {
                var start = 0
                while (start < sentence.length) {
                    val end = minOf(start + maxChars, sentence.length)
                    parts.add(sentence.substring(start, end).trim())
                    start = end
                }
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString().trim())
        return parts.filter { it.isNotEmpty() }
    }
}
