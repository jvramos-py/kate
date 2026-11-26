package dev.notnull

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URLEncoder

data class FandomPageRef(
    val pageId: Long,
    val title: String,
    val isCategory: Boolean,
    val namespace: Int = 0,
)

class FandomClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO)

    suspend fun categoryMembers(
        wiki: String,
        category: String,
        continueFrom: String? = null,
    ): Pair<List<FandomPageRef>, String?> {
        val params =
            mutableMapOf(
                "action" to "query",
                "list" to "categorymembers",
                "cmtitle" to category,
                "cmlimit" to "500",
                "cmtype" to "page|subcat",
            )
        if (continueFrom != null) params["cmcontinue"] = continueFrom

        val payload = api(wiki, params)
        val members =
            payload["query"]?.jsonObject?.get("categorymembers")?.jsonArray?.mapNotNull { element ->
                val obj = element.jsonObject
                val ns = obj["ns"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val pageId = obj["pageid"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                FandomPageRef(pageId, title, ns == 14, ns)
            } ?: emptyList()
        val next =
            payload["continue"]
                ?.jsonObject
                ?.get("cmcontinue")
                ?.jsonPrimitive
                ?.content
        return members to next
    }

    suspend fun allPages(
        wiki: String,
        continueFrom: String? = null,
    ): Pair<List<FandomPageRef>, String?> {
        val params =
            mutableMapOf(
                "action" to "query",
                "list" to "allpages",
                "apnamespace" to "0",
                "aplimit" to "500",
            )
        if (continueFrom != null) params["apcontinue"] = continueFrom

        val payload = api(wiki, params)
        val pages =
            payload["query"]?.jsonObject?.get("allpages")?.jsonArray?.mapNotNull { element ->
                val obj = element.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val pageId = obj["pageid"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                FandomPageRef(pageId, title, false)
            } ?: emptyList()
        val next =
            payload["continue"]
                ?.jsonObject
                ?.get("apcontinue")
                ?.jsonPrimitive
                ?.content
        return pages to next
    }

    suspend fun pageHtml(
        wiki: String,
        title: String,
    ): String? {
        val payload =
            api(
                wiki,
                mapOf(
                    "action" to "parse",
                    "page" to title,
                    "prop" to "text",
                    "redirects" to "1",
                ),
            )
        return payload["parse"]
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.content
    }

    fun pageUrl(
        wiki: String,
        title: String,
    ): String {
        val encoded =
            URLEncoder
                .encode(title.replace(' ', '_'), Charsets.UTF_8)
                .replace("+", "%20")
        return "https://$wiki.fandom.com/wiki/$encoded"
    }

    private suspend fun api(
        wiki: String,
        params: Map<String, String>,
    ): JsonObject {
        val response =
            client.get("https://$wiki.fandom.com/api.php") {
                header(HttpHeaders.UserAgent, "KateDiscordBot/1.0 (private server; contact: local)")
                parameter("format", "json")
                parameter("formatversion", "2")
                params.forEach { (key, value) -> parameter(key, value) }
            }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Fandom API HTTP ${response.status.value}: ${text.take(300)}")
        }
        return json.parseToJsonElement(text).jsonObject
    }
}

object HtmlText {
    fun toText(html: String): String {
        val doc = Jsoup.parse(html)
        val root = doc.selectFirst("div.mw-parser-output") ?: doc.body()
        root
            .select(
                "script, style, noscript, .navbox, .toc, .mw-editsection, .reference, " +
                    ".mw-references-wrap, .catlinks, .printfooter, .noprint, .mw-collapsible-toggle, " +
                    ".wds-tabber, .ad, .gpt-ad, .fandom-community-header__local-navigation",
            ).remove()

        val out = StringBuilder()
        render(root, out)
        return clean(out.toString())
    }

    private fun render(
        node: Node,
        out: StringBuilder,
    ) {
        when (node) {
            is TextNode -> {
                out.append(node.text())
            }

            is Element -> {
                when (node.tagName().lowercase()) {
                    "script", "style", "noscript", "img", "figure", "audio", "video", "svg" -> {
                        return
                    }

                    "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        val text = node.text().trim()
                        if (text.isNotEmpty()) out.append("\n\n## ").append(text).append('\n')
                    }

                    "p" -> {
                        val text = node.text().trim()
                        if (text.isNotEmpty()) out.append('\n').append(text).append('\n')
                    }

                    "li" -> {
                        val text = node.text().trim()
                        if (text.isNotEmpty()) out.append("\n- ").append(text)
                    }

                    "br" -> {
                        out.append('\n')
                    }

                    "table" -> {
                        out.append('\n')
                        node.select("tr").forEach { row ->
                            val cells = row.select("th, td").map { it.text().trim() }.filter { it.isNotEmpty() }
                            if (cells.isNotEmpty()) out.append(cells.joinToString(" | ")).append('\n')
                        }
                        out.append('\n')
                    }

                    else -> {
                        node.childNodes().forEach { render(it, out) }
                    }
                }
            }
        }
    }

    private fun clean(text: String): String {
        val lines =
            text
                .replace('\u00A0', ' ')
                .replace(Regex("\\[edit]", RegexOption.IGNORE_CASE), "")
                .lines()
                .map { it.replace(Regex("\\s+"), " ").trim() }

        val out = StringBuilder()
        var lastBlank = false
        for (line in lines) {
            if (line.isBlank()) {
                if (!lastBlank) out.append('\n')
                lastBlank = true
            } else {
                out.append(line).append('\n')
                lastBlank = false
            }
        }
        return out.toString().trim()
    }
}
