package dev.notnull

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds

private data class WikiConfig(
    val key: String,
    val wiki: String,
    val categories: List<String>,
    val excludedTitlePrefixes: List<String> = emptyList(),
)

private val wikis =
    listOf(
        WikiConfig(
            key = "helldivers2",
            wiki = "helldivers",
            // A wiki do Helldivers cobre HD1 e HD2. As categorias genéricas
            // (Category:Weapons, Category:Stratagems, ...) contêm as subcategorias
            // "(Helldivers)" e "(Helldivers 2)", então usar as genéricas misturava
            // as duas gerações na mesma chave "helldivers2". Aqui usamos apenas as
            // categorias específicas de HD2.
            categories =
                listOf(
                    "Category:Helldivers 2",
                    "Category:Weapons (Helldivers 2)",
                    "Category:Stratagems (Helldivers 2)",
                    "Category:Enemies (Helldivers 2)",
                    "Category:Armor (Helldivers 2)",
                    "Category:DLC Packs (Helldivers 2)",
                    "Category:Objectives (Helldivers 2)",
                    "Category:Automatons",
                    "Category:Terminids",
                    "Category:Illuminate",
                    "Category:Warbond",
                    "Category:Planets",
                    "Category:Missions",
                ),
            // Páginas de conteúdo do primeiro jogo são prefixadas com
            // "Helldivers 1:" na wiki. Mesmo que alguma categoria traga uma
            // página assim, ela nunca deve entrar na base do HD2.
            excludedTitlePrefixes = listOf("Helldivers 1:"),
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
        WikiConfig(
            key = "sekiro",
            wiki = "sekiro-shadows-die-twice",
            // Wiki dedicada: todas as páginas de conteúdo são de Sekiro.
            categories = emptyList(),
        ),
        WikiConfig(
            key = "bloodborne",
            wiki = "bloodborne",
            // Wiki dedicada: todas as páginas de conteúdo são de Bloodborne.
            categories = emptyList(),
        ),
        WikiConfig(
            key = "wuchang",
            wiki = "wuchang-fallen-feathers",
            // Wiki dedicada: todas as páginas de conteúdo são de Wuchang.
            categories = emptyList(),
        ),
        WikiConfig(
            key = "yakuza0",
            wiki = "yakuza",
            // A wiki de Yakuza cobre a série inteira; usamos apenas as
            // categorias específicas de Yakuza 0.
            categories =
                listOf(
                    "Category:Yakuza 0",
                    "Category:Yakuza 0 Characters",
                    "Category:Yakuza 0 Bosses",
                    "Category:Yakuza 0 Final Bosses",
                    "Category:Yakuza 0 Antagonists",
                    "Category:Yakuza 0 Supporting Characters",
                    "Category:Yakuza 0 Playable Characters",
                    "Category:Yakuza 0 Substories",
                    "Category:Yakuza 0 Substory Characters",
                    "Category:Yakuza 0 Chapter Summaries",
                    "Category:Yakuza 0 Locations",
                    "Category:Yakuza 0 Weapons",
                    "Category:Yakuza 0 Items",
                    "Category:Yakuza 0 Accessories",
                    "Category:Yakuza 0 Abilities",
                    "Category:Yakuza 0 Minigames",
                    "Category:Yakuza 0 Organizations",
                    "Category:Yakuza 0 Karaoke",
                    "Category:Yakuza 0 Food",
                    "Category:Yakuza 0 Masters",
                    "Category:Yakuza 0 Friends",
                    "Category:Yakuza 0 AI Partners",
                ),
        ),
        WikiConfig(
            key = "baldursgate3",
            wiki = "baldursgate",
            // A wiki de Baldur's Gate cobre BG1, BG2 e BG3. A convenção do site
            // para conteúdo do terceiro jogo é o sufixo "(BG3)".
            categories =
                listOf(
                    "Category:Baldur's Gate III",
                    "Category:Companions (BG3)",
                    "Category:Origin characters (BG3)",
                    "Category:Classes (BG3)",
                    "Category:Races (BG3)",
                    "Category:Spells (BG3)",
                    "Category:Creatures (BG3)",
                    "Category:Items (BG3)",
                    "Category:Quests (BG3)",
                    "Category:Game mechanics (BG3)",
                    "Category:Backgrounds (BG3)",
                    "Category:Abilities (BG3)",
                    "Category:Light Armours (BG3)",
                ),
        ),
    )

fun main(args: Array<String>): Unit =
    runBlocking {
        val requested = args.filterNot { it.startsWith("--") }.ifEmpty { listOf("helldivers2") }
        val force = args.contains("--force")
        val resume = args.contains("--resume")
        val limit =
            args
                .firstOrNull { it.startsWith("--limit=") }
                ?.substringAfter("--limit=")
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
        val minutes =
            args
                .firstOrNull { it.startsWith("--minutes=") }
                ?.substringAfter("--minutes=")
                ?.toLongOrNull()
                ?.takeIf { it >= 0 }
                ?: if (resume) 30L else null
        if (minutes != null && !resume) {
            println("Aviso: --minutes ignorado sem --resume (sem checkpoint não há como retomar).")
        }
        val budgetMs = minutes?.takeIf { it > 0 }?.times(60_000L)
        val startedAt = System.currentTimeMillis()
        val all = args.contains("--all")

        val selected = if (all) wikis else wikis.filter { it.key in requested }
        if (selected.isEmpty()) {
            println("Nenhum jogo válido. Use: ${wikis.joinToString { it.key }} ou --all")
            return@runBlocking
        }

        val fandom = FandomClient()
        val vector = VectorClient()
        val ollama = OllamaClient()

        val limitLabel = limit?.toString() ?: "ilimitado"
        val timeLabel = budgetMs?.let { "${it / 60_000}min" } ?: "ilimitado"
        println(
            "Ingestão RAG: ${selected.joinToString { it.key }} | limite=$limitLabel | " +
                "tempo=$timeLabel | resume=$resume | force=$force",
        )
        println("Chunks existentes: ${vector.knowledgeCount()}")

        for (config in selected) {
            if (config.key == "helldivers2") {
                val removed = vector.deleteHelldivers1Content()
                if (removed > 0) {
                    println("Limpeza: removidos $removed chunks legados de Helldivers 1")
                }
            }

            println("\n=== ${GameDetector.label(config.key)} (${config.wiki}.fandom.com) ===")
            val refs = collectPages(fandom, config)
            val allPages =
                refs.values
                    .filterNot { it.isCategory }
                    .sortedBy { it.title }
                    .let { if (limit != null) it.take(limit) else it }
            val signature = Hashing.sha256(allPages.joinToString("\n") { it.title })

            if (force) vector.clearIngestState(config.key)

            var startIndex = 0
            if (resume) {
                val state = vector.ingestState(config.key)
                if (state != null && state.signature == signature && state.total == allPages.size) {
                    startIndex = state.lastIndex.coerceIn(0, allPages.size)
                    if (startIndex > 0) {
                        println("Retomando ${config.key} no índice $startIndex/${allPages.size}")
                    }
                } else if (state != null) {
                    println("Checkpoint de ${config.key} descartado: a lista de páginas mudou")
                    vector.clearIngestState(config.key)
                }
            }

            println("Páginas encontradas: ${refs.size} | processando: ${allPages.size} | de $startIndex")
            var ingested = 0
            var skipped = 0
            var failed = 0
            var processed = startIndex
            var stoppedByBudget = false

            for (index in startIndex until allPages.size) {
                if (budgetMs != null && System.currentTimeMillis() - startedAt >= budgetMs) {
                    vector.saveIngestState(config.key, signature, index, allPages.size)
                    val pct = if (allPages.size == 0) 100 else (100.0 * index / allPages.size).toInt()
                    println("Checkpoint: ${config.key} $index/${allPages.size} ($pct%) — índice salvo")
                    stoppedByBudget = true
                    break
                }

                val ref = allPages[index]
                val position = "${index + 1}/${allPages.size}"

                processPage@ do {
                    try {
                        val html = fandom.pageHtml(config.wiki, ref.title)
                        if (html == null) {
                            println("[$position] sem HTML: ${ref.title}")
                            failed++
                            break@processPage
                        }

                        val content = HtmlText.toText(html)
                        if (content.length < 200) {
                            println("[$position] conteúdo curto: ${ref.title}")
                            skipped++
                            break@processPage
                        }

                        val sourceHash = Hashing.sha256(content)
                        if (!force && vector.pageHash(config.key, ref.pageId) == sourceHash) {
                            println("[$position] sem mudanças: ${ref.title}")
                            skipped++
                            break@processPage
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
                            break@processPage
                        }

                        val embeddings = embedChunks(ollama, chunks)
                        if (embeddings == null) {
                            println("[$position] embeddings falharam: ${ref.title}")
                            failed++
                            break@processPage
                        }

                        vector.upsertPageAndChunks(page, chunks, embeddings)
                        ingested++
                        println("[$position] ok: ${ref.title} (${chunks.size} chunks)")
                    } catch (e: Exception) {
                        failed++
                        println("[$position] erro em ${ref.title}: ${e.message}")
                    }
                } while (false)

                processed = index + 1
                if (resume) {
                    vector.saveIngestState(config.key, signature, processed, allPages.size)
                }
                delay(200.milliseconds)
            }

            val pct = if (allPages.size == 0) 100 else (100.0 * processed / allPages.size).toInt()
            println(
                "Resumo ${config.key}: ingeridas=$ingested ignoradas=$skipped falhas=$failed | " +
                    "índice=$processed/${allPages.size} ($pct%)",
            )
            if (!stoppedByBudget && processed >= allPages.size) {
                vector.clearIngestState(config.key)
                println("Concluído: ${config.key}")
            }
            if (stoppedByBudget) break
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
                    if (depth > 0 && !isExcluded(member.title, config)) fetchCategory(member.title, depth - 1)
                } else if (member.namespace == 0 && !isExcluded(member.title, config)) {
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
            members.filterNot { isExcluded(it.title, config) }.forEach { pages.putIfAbsent(it.title, it) }
            continuation = next
        } while (continuation != null)
    }

    return pages
}

private fun isExcluded(
    title: String,
    config: WikiConfig,
): Boolean = config.excludedTitlePrefixes.any { title.startsWith(it) }

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
