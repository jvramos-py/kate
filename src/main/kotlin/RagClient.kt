package dev.notnull

class RagClient(
    private val ollama: OllamaClient,
    private val vector: VectorClient,
    private val searxng: SearxngClient = SearxngClient(),
) {
    suspend fun retrieve(
        question: String,
        mode: ResponseMode,
    ): RagContext? {
        val game = GameDetector.detect(question)
        val limit = if (mode == ResponseMode.VOICE) 4 else 6

        if (needsFreshInfo(question)) {
            val web = webContext(question, game, limit)
            if (web != null) return web
        }

        val local =
            runCatching { localContext(question, game, limit) }.getOrElse {
                println("Busca local falhou: ${it.message}")
                null
            }
        if (local != null) return local

        return webContext(question, game, limit)
    }

    private fun needsFreshInfo(question: String): Boolean = FRESH_INFO_PATTERN.containsMatchIn(question)

    private suspend fun localContext(
        question: String,
        game: String?,
        limit: Int,
    ): RagContext? {
        val embedding =
            runCatching {
                ollama.getEmbeddings(EmbeddingText.query(question))
            }.getOrNull()

        val vectorHits =
            if (embedding == null) {
                emptyList()
            } else {
                runCatching {
                    vector.searchKnowledge(embedding, game, limit = limit + 2, minScore = 0.12)
                }.getOrElse {
                    println("Busca vetorial falhou: ${it.message}")
                    emptyList()
                }
            }

        val keyword =
            runCatching {
                vector.keywordSearch(question, game, limit = limit)
            }.getOrElse {
                println("Busca por palavra-chave falhou: ${it.message}")
                emptyList()
            }

        val hits = mergeHits(fuse(listOf(vectorHits, keyword), limit + 3)).take(limit)
        if (hits.isEmpty()) return null

        val prompt = StringBuilder()
        val sources = mutableListOf<RagSource>()
        var used = 0

        for ((index, hit) in hits.withIndex()) {
            val label = index + 1
            val header =
                "[$label] ${GameDetector.label(hit.game)} — ${hit.title}" +
                    (if (hit.section.isBlank()) "" else " > ${hit.section}") + "\n"
            if (used + header.length + hit.content.length > 6500 && sources.isNotEmpty()) break

            prompt.append(header)
            prompt.append(hit.content.trim())
            prompt.append("\n\n")
            used += header.length + hit.content.length + 2
            sources.add(
                RagSource(
                    index = label,
                    game = GameDetector.label(hit.game),
                    title = hit.title,
                    section = hit.section,
                    url = hit.url,
                ),
            )
        }

        if (sources.isEmpty()) return null
        return RagContext(
            prompt = prompt.toString().trim(),
            sources = sources,
            hasSpoiler = hits.any { it.spoiler },
            fromWeb = false,
        )
    }

    private suspend fun webContext(
        question: String,
        game: String?,
        limit: Int,
    ): RagContext? {
        val query = if (game != null) "$question ${GameDetector.label(game)}" else question
        val results = searxng.search(query, limit)
        if (results.isEmpty()) return null

        val prompt = StringBuilder()
        val sources = mutableListOf<RagSource>()
        var used = 0

        for ((index, result) in results.withIndex()) {
            val label = index + 1
            val header = "[$label] ${result.title.trim()}\n${result.url}\n"
            if (used + header.length + result.content.length > 6500 && sources.isNotEmpty()) break

            prompt.append(header)
            prompt.append(result.content.trim())
            prompt.append("\n\n")
            used += header.length + result.content.length + 2
            sources.add(
                RagSource(
                    index = label,
                    game = GameDetector.label(game),
                    title = result.title.trim(),
                    section = "",
                    url = result.url,
                ),
            )
        }

        if (sources.isEmpty()) return null
        val spoiler =
            game != null && game != "helldivers2" &&
                SpoilerDetector.isSpoiler(game, question, "")
        return RagContext(
            prompt = prompt.toString().trim(),
            sources = sources,
            hasSpoiler = spoiler,
            fromWeb = true,
        )
    }

    private fun mergeHits(hits: List<KnowledgeHit>): List<KnowledgeHit> {
        val grouped = linkedMapOf<String, KnowledgeHit>()

        for (hit in hits) {
            val key = "${hit.game}|${hit.title}|${hit.section}"
            val existing = grouped[key]
            if (existing == null) {
                grouped[key] =
                    hit.copy(
                        content =
                            hit.content
                                .removePrefix(hit.section)
                                .trim()
                                .take(2200),
                    )
                continue
            }
            if (existing.content.length >= 2200) continue

            val addition =
                hit.content
                    .drop(overlapLength(existing.content, hit.content))
                    .removePrefix(hit.section)
                    .trim()
            if (addition.isEmpty()) continue

            val merged = "${existing.content}\n\n$addition".take(2200)
            grouped[key] = existing.copy(content = merged, score = maxOf(existing.score, hit.score))
        }

        return grouped.values.toList()
    }

    private fun overlapLength(
        existing: String,
        next: String,
    ): Int {
        val max = minOf(500, existing.length, next.length)
        for (size in max downTo 1) {
            if (existing.regionMatches(existing.length - size, next, 0, size)) return size
        }
        return 0
    }

    private fun fuse(
        lists: List<List<KnowledgeHit>>,
        limit: Int,
    ): List<KnowledgeHit> {
        val scores = mutableMapOf<String, Double>()
        val byId = mutableMapOf<String, KnowledgeHit>()

        lists.forEach { hits ->
            hits.forEachIndexed { rank, hit ->
                byId[hit.id] = hit
                scores[hit.id] = (scores[hit.id] ?: 0.0) + 1.0 / (60.0 + rank)
            }
        }

        return scores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { entry ->
                byId[entry.key]?.copy(score = entry.value)
            }
    }

    private companion object {
        val FRESH_INFO_PATTERN =
            Regex(
                "\\b(patch notes?|hotfix|update|atualiza[cç][aã]o|nerf|buff|meta|balance(amento)?)\\b",
                RegexOption.IGNORE_CASE,
            )
    }
}
