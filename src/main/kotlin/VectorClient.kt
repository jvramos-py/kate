package dev.notnull

import com.pgvector.PGvector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.postgresql.util.PGobject
import java.sql.ResultSet

data class IngestState(
    val signature: String,
    val lastIndex: Int,
    val total: Int,
)

class VectorClient(
    url: String = Config.databaseUrl,
    user: String = Config.databaseUser,
    password: String = Config.databasePassword,
) {
    private val db =
        Database.connect(
            url = url,
            driver = "org.postgresql.Driver",
            user = user,
            password = password,
        )

    object Embeddings : Table("embeddings") {
        val id = text("id")
        val collectionId = text("collection_id")
        val embedding = registerColumn<PGvector>("embedding", VectorColumnType())
        val document = text("document")
        val metadata = text("metadata")
        val timestamp = long("timestamp").clientDefault { System.currentTimeMillis() }

        override val primaryKey = PrimaryKey(id)
    }

    object RagPages : Table("rag_pages") {
        val id = text("id")
        val game = text("game")
        val pageId = long("page_id")
        val title = text("title")
        val url = text("url")
        val content = text("content")
        val sourceHash = text("source_hash")
        val fetchedAt = long("fetched_at")

        override val primaryKey = PrimaryKey(id)
    }

    object KnowledgeChunks : Table("knowledge_chunks") {
        val id = text("id")
        val pageId = long("page_id")
        val game = text("game")
        val title = text("title")
        val section = text("section")
        val url = text("url")
        val content = text("content")
        val embedding = registerColumn<PGvector>("embedding", VectorColumnType(768))
        val metadata = text("metadata")
        val spoiler = bool("spoiler")
        val updatedAt = long("updated_at")

        override val primaryKey = PrimaryKey(id)
    }

    object IngestStates : Table("rag_ingest_state") {
        val game = text("game")
        val signature = text("signature")
        val lastIndex = integer("last_index")
        val total = integer("total")
        val updatedAt = long("updated_at")

        override val primaryKey = PrimaryKey(game)
    }

    class VectorColumnType(
        private val dimensions: Int? = null,
    ) : ColumnType<PGvector>() {
        override fun sqlType(): String = dimensions?.let { "vector($it)" } ?: "vector"

        override fun valueFromDB(value: Any): PGvector =
            when (value) {
                is PGvector -> {
                    value
                }

                is PGobject -> {
                    check(value.type == "vector") { "Tipo PostgreSQL inesperado: ${value.type}" }
                    PGvector(value.value)
                }

                is String -> {
                    PGvector(value)
                }

                else -> {
                    error("Valor inesperado para coluna vector: ${value::class.qualifiedName}")
                }
            }

        override fun notNullValueToDB(value: PGvector): Any = value
    }

    init {
        transaction(db) {
            exec("CREATE EXTENSION IF NOT EXISTS vector")
            SchemaUtils.create(Embeddings, RagPages, KnowledgeChunks, IngestStates)
            exec("ALTER TABLE embeddings ADD COLUMN IF NOT EXISTS timestamp BIGINT")
            exec("CREATE INDEX IF NOT EXISTS knowledge_chunks_embedding_hnsw ON knowledge_chunks USING hnsw (embedding vector_cosine_ops)")
            exec("CREATE INDEX IF NOT EXISTS knowledge_chunks_game_idx ON knowledge_chunks (game)")
            exec(
                "CREATE INDEX IF NOT EXISTS knowledge_chunks_fts_idx ON knowledge_chunks USING GIN (to_tsvector('simple', title || ' ' || section || ' ' || content))",
            )
            exec("CREATE INDEX IF NOT EXISTS rag_pages_game_page_idx ON rag_pages (game, page_id)")
        }
    }

    fun addDocument(
        collectionId: String,
        id: String,
        embedding: List<Float>,
        document: String,
        metadata: JsonObject? = null,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        transaction(db) {
            Embeddings.upsert {
                it[Embeddings.id] = id
                it[Embeddings.collectionId] = collectionId
                it[Embeddings.embedding] = PGvector(embedding.toFloatArray())
                it[Embeddings.document] = document
                it[Embeddings.metadata] = metadata?.toString() ?: "{}"
                it[Embeddings.timestamp] = timestamp
            }
        }
    }

    fun getChatDocument(collectionId: String): Pair<String, JsonObject>? =
        transaction(db) {
            Embeddings
                .selectAll()
                .where { Embeddings.id eq collectionId }
                .map { it[Embeddings.document] to Json.decodeFromString<JsonObject>(it[Embeddings.metadata]) }
                .firstOrNull()
        }

    fun pageHash(
        game: String,
        pageId: Long,
    ): String? =
        transaction(db) {
            RagPages
                .select(RagPages.sourceHash)
                .where { (RagPages.game eq game) and (RagPages.pageId eq pageId) }
                .singleOrNull()
                ?.get(RagPages.sourceHash)
        }

    fun ingestState(game: String): IngestState? =
        transaction(db) {
            IngestStates
                .select(IngestStates.signature, IngestStates.lastIndex, IngestStates.total)
                .where { IngestStates.game eq game }
                .singleOrNull()
                ?.let {
                    IngestState(
                        signature = it[IngestStates.signature],
                        lastIndex = it[IngestStates.lastIndex],
                        total = it[IngestStates.total],
                    )
                }
        }

    fun saveIngestState(
        game: String,
        signature: String,
        lastIndex: Int,
        total: Int,
    ) {
        transaction(db) {
            IngestStates.upsert {
                it[IngestStates.game] = game
                it[IngestStates.signature] = signature
                it[IngestStates.lastIndex] = lastIndex
                it[IngestStates.total] = total
                it[IngestStates.updatedAt] = System.currentTimeMillis()
            }
        }
    }

    fun clearIngestState(game: String) {
        transaction(db) {
            IngestStates.deleteWhere { IngestStates.game eq game }
        }
    }

    fun upsertPageAndChunks(
        page: RagPage,
        chunks: List<RagChunk>,
        embeddings: List<List<Float>>,
    ) {
        require(chunks.size == embeddings.size) {
            "Quantidade de chunks (${chunks.size}) diferente de embeddings (${embeddings.size})"
        }
        transaction(db) {
            RagPages.upsert {
                it[RagPages.id] = "${page.game}:${page.pageId}"
                it[RagPages.game] = page.game
                it[RagPages.pageId] = page.pageId
                it[RagPages.title] = page.title
                it[RagPages.url] = page.url
                it[RagPages.content] = page.content
                it[RagPages.sourceHash] = page.sourceHash
                it[RagPages.fetchedAt] = page.fetchedAt
            }

            KnowledgeChunks.deleteWhere {
                (KnowledgeChunks.game eq page.game) and (KnowledgeChunks.pageId eq page.pageId)
            }

            chunks.forEachIndexed { index, chunk ->
                KnowledgeChunks.insert {
                    it[KnowledgeChunks.id] = chunk.id
                    it[KnowledgeChunks.pageId] = chunk.pageId
                    it[KnowledgeChunks.game] = chunk.game
                    it[KnowledgeChunks.title] = chunk.title
                    it[KnowledgeChunks.section] = chunk.section
                    it[KnowledgeChunks.url] = chunk.url
                    it[KnowledgeChunks.content] = chunk.content
                    it[KnowledgeChunks.embedding] = PGvector(embeddings[index].toFloatArray())
                    it[KnowledgeChunks.metadata] = chunk.metadata
                    it[KnowledgeChunks.spoiler] = chunk.spoiler
                    it[KnowledgeChunks.updatedAt] = System.currentTimeMillis()
                }
            }
        }
    }

    fun deleteHelldivers1Content(): Int =
        transaction(db) {
            val chunks =
                KnowledgeChunks.deleteWhere {
                    (KnowledgeChunks.game eq "helldivers2") and (KnowledgeChunks.title like "Helldivers 1:%")
                }
            RagPages.deleteWhere {
                (RagPages.game eq "helldivers2") and (RagPages.title like "Helldivers 1:%")
            }
            chunks
        }

    fun searchKnowledge(
        embedding: List<Float>,
        game: String?,
        limit: Int,
        minScore: Double = 0.0,
    ): List<KnowledgeHit> =
        transaction(db) {
            val vector = PGvector(embedding.toFloatArray())
            val gameFilter = if (game != null) "AND game = ?" else ""
            val legacyFilter = legacyTitleFilter()
            val sql =
                """
                SELECT id, page_id, game, title, section, url, content, spoiler, metadata,
                       1 - (embedding <=> ?::vector) AS score
                FROM knowledge_chunks
                WHERE 1 = 1 $gameFilter $legacyFilter
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """.trimIndent()

            val args = mutableListOf<Pair<IColumnType<*>, Any?>>()
            args.add(VectorColumnType(768) to vector)
            if (game != null) args.add(TextColumnType() to game)
            args.add(VectorColumnType(768) to vector)
            args.add(IntegerColumnType() to limit)

            exec(sql, args) { rs ->
                val results = mutableListOf<KnowledgeHit>()
                while (rs.next()) {
                    val score = rs.getDouble("score")
                    if (score >= minScore) results.add(readHit(rs, score))
                }
                results
            } ?: emptyList()
        }

    fun keywordSearch(
        query: String,
        game: String?,
        limit: Int,
    ): List<KnowledgeHit> =
        transaction(db) {
            val term = query.trim()
            if (term.isBlank()) return@transaction emptyList()

            val gameFilter = if (game != null) "AND game = ?" else ""
            val legacyFilter = legacyTitleFilter()
            val sql =
                """
                SELECT id, page_id, game, title, section, url, content, spoiler, metadata,
                       ts_rank_cd(
                           to_tsvector('simple', title || ' ' || section || ' ' || content),
                           websearch_to_tsquery('simple', ?)
                       ) AS score
                FROM knowledge_chunks
                WHERE to_tsvector('simple', title || ' ' || section || ' ' || content)
                      @@ websearch_to_tsquery('simple', ?)
                $gameFilter
                $legacyFilter
                ORDER BY score DESC
                LIMIT ?
                """.trimIndent()

            val args = mutableListOf<Pair<IColumnType<*>, Any?>>()
            args.add(TextColumnType() to term)
            args.add(TextColumnType() to term)
            if (game != null) args.add(TextColumnType() to game)
            args.add(IntegerColumnType() to limit)

            exec(sql, args) { rs ->
                val results = mutableListOf<KnowledgeHit>()
                while (rs.next()) results.add(readHit(rs, rs.getDouble("score")))
                results
            } ?: emptyList()
        }

    fun knowledgeCount(): Long =
        transaction(db) {
            KnowledgeChunks.selectAll().count()
        }

    private fun legacyTitleFilter(): String = "AND title NOT LIKE 'Helldivers 1:%'"

    private fun readHit(
        rs: ResultSet,
        score: Double,
    ): KnowledgeHit =
        KnowledgeHit(
            id = rs.getString("id"),
            pageId = rs.getLong("page_id").toString(),
            game = rs.getString("game"),
            title = rs.getString("title"),
            section = rs.getString("section"),
            url = rs.getString("url"),
            content = rs.getString("content"),
            spoiler = rs.getBoolean("spoiler"),
            metadata = rs.getString("metadata"),
            score = score,
        )
}
