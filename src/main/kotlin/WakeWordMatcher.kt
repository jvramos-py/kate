package dev.notnull

import java.text.Normalizer

/**
 * Reconhece a palavra de ativação mesmo quando o Whisper transcreve o nome de forma diferente
 * (ex.: "Queite", "Cage", "Keite", "Queit").
 */
object WakeWordMatcher {
    private val variants =
        setOf(
            "kate",
            "kati",
            "katy",
            "katie",
            "katee",
            "katey",
            "kat",
            "keite",
            "keiti",
            "keit",
            "keyte",
            "keyt",
            "kaite",
            "kait",
            "kete",
            "keti",
            "kety",
            "ket",
            "keth",
            "kett",
            "queite",
            "queit",
            "quete",
            "queti",
            "cate",
            "cati",
            "cage",
            "gati",
        )

    private val prefixes = listOf("ei", "oi", "ok", "hey", "e", "o")

    private val nonAlphanumeric = Regex("[^a-z0-9]+")
    private val combiningMarks = Regex("\\p{M}+")

    fun isCalled(text: String): Boolean {
        val tokens = normalize(text).split(' ').filter { it.isNotBlank() }

        for (token in tokens) {
            if (token in variants) {
                return true
            }
            for (prefix in prefixes) {
                if (token.startsWith(prefix) && token.removePrefix(prefix) in variants) {
                    return true
                }
            }
        }

        return false
    }

    private fun normalize(text: String): String {
        val lower = text.lowercase()
        val withoutAccents =
            Normalizer
                .normalize(lower, Normalizer.Form.NFD)
                .replace(combiningMarks, "")
        return withoutAccents.replace(nonAlphanumeric, " ").trim()
    }
}
