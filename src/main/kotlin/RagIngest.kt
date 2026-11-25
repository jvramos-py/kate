package dev.notnull

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds

private data class WikiConfig(
    val key: String,
    val wiki: String,
    val categories: List<String>,
)

private val wikis =
    listOf(
        WikiConfig(
            key = "helldivers2",
            wiki = "helldivers",
            categories =
                listOf(
                    "Category:Helldivers_2",
                    "Category:Weapons",
                    "Category:Stratagems",
                    "Category:Enemies",
                    "Category:Armor",
                    "Category:Warbonds",
                    "Category:Planets",
                    "Category:Missions",
                    "Category:Ship_Modules",
                ),
        ),
        WikiConfig(
            key = "darksouls1",
            wiki = "darksouls",
            categories =
                listOf(
                    "Category:Dark_Souls",
                    "Category:Weapons",
                    "Category:Armor",
                    "Category:Rings",
                    "Category:Spells",
                    "Category:Bosses",
                    "Category:Locations",
                    "Category:NPCs",
                ),
        ),
        WikiConfig(
            key = "darksouls2",
            wiki = "darksouls",
            categories =
                listOf(
                    "Category:Dark_Souls_II",
                    "Category:Weapons",
                    "Category:Armor",
                    "Category:Rings",
                    "Category:Spells",
                    "Category:Bosses",
                    "Category:Locations",
                    "Category:NPCs",
                ),
        ),
        WikiConfig(
            key = "darksouls3",
            wiki = "darksouls",
            categories =
                listOf(
                    "Category:Dark_Souls_III",
                    "Category:Weapons",
                    "Category:Armor",
                    "Category:Rings",
                    "Category:Spells",
                    "Category:Bosses",
                    "Category:Locations",
                    "Category:NPCs",
                ),
        ),
        WikiConfig(
            key = "eldenring",
            wiki = "eldenring",
            categories =
                listOf(
                    "Category:Elden_Ring",
                    "Category:Weapons",
                    "Category:Armor",
                    "Category:Talismans",
                    "Category:Spells",
                    "Category:Incantations",
                    "Category:Ashes_of_War",
                    "Category:Bosses",
                    "Category:Locations",
                    "Category:NPCs",
                ),
        ),
    )

fun main(args: Array<String>): Unit =
    runBlocking {
        val requested = args.filterNot { it.startsWith("--") }.ifEmpty { listOf("helldivers2") }
        val force = args.contains("--force")
        val limit =
            args
                .firstOrNull { it.startsWith("--limit=") }
                ?.substringAfter("--limit=")
                ?.toIntOrNull()
                ?: 600
        val all = args.contains("--all")

        val selected = if (all) wikis else wikis.filter { it.key in requested }
        if (selected.isEmpty()) {
            println("Nenhum jogo válido. Use: helldivers2, darksouls1, darksouls2, darksouls3, eldenring ou --all")
            return@runBlocking
        }

        val fandom = FandomClient()
        val vector = VectorClient()
        val ollama = OllamaClient()

        println("Ingestão RAG: ${selected.joinToString { it.key }} | limite=$limit | force=$force")
        println("Chunks existentes: ${vector.knowledgeCount()}")

        for (config in selected) {
            println("\n=== ${GameDetector.label(config.key)} (${config.wiki}.fandom.com) ===")
            val refs = collectPages(fandom, config)
            val pages =
                refs.values
                    .filterNot { it.isCategory }
                    .sortedBy { it.title }
                    .take(limit)

            println("Páginas encontradas: ${refs.size} | processando: ${pages.size}")
            var ingested = 0
            var skipped = 0
            var failed = 0

            for ((index, ref) in pages.withIndex()) {
                val position = "${index + 1}/${pages.size}"
                try {
                    val html = fandom.pageHtml(config.wiki, ref.title)
                    if (html == null) {
                        println("[$position] sem HTML: ${ref.title}")
                        failed++
                        continue
                    }

                    val content = HtmlText.toText(html)
                    if (content.length < 200) {
                        println("[$position] conteúdo curto: ${ref.title}")
                        skipped++
                        continue
                    }

                    val sourceHash = Hashing.sha256(content)
                    if (!force && vector.pageHash(config.key, ref.pageId) == sourceHash) {
                        println("[$position] sem mudanças: ${ref.title}")
                        skipped++
                        continue
                    }

                    val page =
                        RagPage(
                            game = config.key,
                            pageId = ref.pageId,
                            title = ref.title,
                            url = fandom.pageUrl(config.wiki, ref.title),
                            content = content,
                            sourceHash = sourceHash,
                        )
                    val chunks = Chunker.chunkPage(page)
                    if (chunks.isEmpty()) {
                        println("[$position] sem chunks: ${ref.title}")
                        skipped++
                        continue
                    }

                    val embeddings = embedChunks(ollama, chunks)
                    if (embeddings == null) {
                        println("[$position] embeddings falharam: ${ref.title}")
                        failed++
                        continue
                    }

                    vector.upsertPageAndChunks(page, chunks, embeddings)
                    ingested++
                    println("[$position] ok: ${ref.title} (${chunks.size} chunks)")
                } catch (e: Exception) {
                    failed++
                    println("[$position] erro em ${ref.title}: ${e.message}")
                }
                delay(200.milliseconds)
            }

            println("Resumo ${config.key}: ingeridas=$ingested ignoradas=$skipped falhas=$failed")
        }

        println("\nTotal de chunks: ${vector.knowledgeCount()}")
    }

private suspend fun collectPages(
    fandom: FandomClient,
    config: WikiConfig,
): Map<String, FandomPageRef> {
    val pages = linkedMapOf<String, FandomPageRef>()

    suspend fun fetchCategory(
        category: String,
        depth: Int,
    ) {
        var continuation: String? = null
        do {
            val (members, next) = fandom.categoryMembers(config.wiki, category, continuation)
            for (member in members) {
                if (member.isCategory) {
                    if (depth > 0) fetchCategory(member.title, depth - 1)
                } else {
                    pages.putIfAbsent(member.title, member)
                }
            }
            continuation = next
        } while (continuation != null)
    }

    for (category in config.categories) {
        try {
            fetchCategory(category, depth = 1)
            println("Categoria ok: $category (${pages.size} páginas)")
        } catch (e: Exception) {
            println("Categoria falhou: $category — ${e.message}")
        }
    }

    if (pages.isEmpty()) {
        println("Nenhuma categoria retornou páginas; usando allpages")
        var continuation: String? = null
        do {
            val (members, next) = fandom.allPages(config.wiki, continuation)
            members.forEach { pages.putIfAbsent(it.title, it) }
            continuation = next
        } while (continuation != null)
    }

    return pages
}

private suspend fun embedChunks(
    ollama: OllamaClient,
    chunks: List<RagChunk>,
): List<List<Float>>? {
    val result = mutableListOf<List<Float>>()
    for (batch in chunks.chunked(16)) {
        val texts = batch.map { it.embeddingText }
        val embeddings = ollama.getEmbeddingsBatch(texts)
        if (embeddings != null && embeddings.size == batch.size) {
            result.addAll(embeddings)
        } else {
            for (chunk in batch) {
                val single = ollama.getEmbeddings(chunk.embeddingText) ?: return null
                result.add(single)
            }
        }
    }
    return result.takeIf { it.size == chunks.size }
}
