package com.proasiste.lite.graph

import com.proasiste.lite.falkor.RedisError
import com.proasiste.lite.text.Tokenizer

/** Runs Redis commands. A function type keeps this class free of how the server is started. */
typealias RedisCall = (Array<out String>) -> Any?

/** The graph in FalkorDB, one graph per project. [documentExists] stops an edge citing a document that isn't in its project. */
class FalkorGraphStore(
    private val redis: RedisCall,
    private val documentExists: (projectId: Long, documentId: Long) -> Boolean,
) : GraphStore {
    private val indexed = HashSet<Long>()

    override fun upsertNode(projectId: Long, type: String, name: String): GraphNode {
        val trimmed = name.trim()
        require(type.isNotBlank() && trimmed.isNotEmpty()) { "A node needs a type and a name." }
        ensureIndexes(projectId)
        val match = Tokenizer.fold(trimmed)
        val row = query(
            projectId,
            """MERGE (n:Entity {key: ${'$'}key})
               ON CREATE SET n.type = ${'$'}type, n.name = ${'$'}name, n.match = ${'$'}match
               RETURN n.key, n.type, n.name""",
            mapOf("key" to "$type:$match", "type" to type, "name" to trimmed, "match" to match),
        ).single()
        return row.toNode()
    }

    override fun addEdge(projectId: Long, fromKey: String, toKey: String, type: String, source: GraphSource): GraphEdge {
        require(type.isNotBlank()) { "An edge needs a type." }
        require(source.page >= 1) { "An edge's source page starts at 1." }
        require(documentExists(projectId, source.documentId)) { "An edge's document must be in its project." }
        val rows = query(
            projectId,
            """MATCH (a:Entity {key: ${'$'}from}), (b:Entity {key: ${'$'}to})
               MERGE (a)-[l:LINK {type: ${'$'}type, document: ${'$'}document, page: ${'$'}page}]->(b)
               RETURN l.type""",
            mapOf("from" to fromKey, "to" to toKey, "type" to type, "document" to source.documentId, "page" to source.page),
        )
        require(rows.isNotEmpty()) { "An edge's nodes must exist." }
        return GraphEdge(fromKey, toKey, type, source)
    }

    override fun findNodes(projectId: Long, name: String, type: String?): List<GraphNode> {
        val parameters = mutableMapOf<String, Any>("match" to Tokenizer.fold(name.trim()))
        type?.let { parameters["type"] = it }
        val filter = if (type != null) " AND n.type = ${'$'}type" else ""
        return query(projectId, "MATCH (n:Entity) WHERE n.match = ${'$'}match$filter RETURN n.key, n.type, n.name ORDER BY n.key", parameters)
            .map { it.toNode() }
    }

    override fun edgesOf(projectId: Long, key: String): List<GraphEdge> =
        query(
            projectId,
            """MATCH (a:Entity)-[l:LINK]->(b:Entity) WHERE a.key = ${'$'}key OR b.key = ${'$'}key
               RETURN a.key, b.key, l.type, l.document, l.page ORDER BY a.key, b.key, l.document, l.page""",
            mapOf("key" to key),
        ).map { row ->
            GraphEdge(
                fromKey = row[0] as String,
                toKey = row[1] as String,
                type = row[2] as String,
                source = GraphSource(row[3] as Long, (row[4] as Long).toInt()),
            )
        }

    override fun neighbors(projectId: Long, key: String, maxHops: Int): List<GraphNeighbor> {
        require(maxHops in 1..MAX_HOPS) { "Hops must be from 1 to $MAX_HOPS." }
        return query(
            projectId,
            """MATCH p = (a:Entity {key: ${'$'}key})-[:LINK*1..$maxHops]-(b:Entity) WHERE b.key <> ${'$'}key
               RETURN b.key, b.type, b.name, min(length(p)) AS hops ORDER BY hops, b.name""",
            mapOf("key" to key),
        ).map { row -> GraphNeighbor(row.toNode(), (row[3] as Long).toInt()) }
    }

    override fun removeDocument(projectId: Long, documentId: Long) {
        query(projectId, "MATCH ()-[l:LINK {document: ${'$'}document}]->() DELETE l", mapOf("document" to documentId))
        query(projectId, "MATCH (n:Entity) WHERE NOT (n)--() DELETE n")
    }

    override fun removeProject(projectId: Long) {
        redis(arrayOf("DEL", graphKey(projectId)))
        indexed -= projectId
    }

    private fun ensureIndexes(projectId: Long) {
        if (projectId in indexed) return
        for (property in listOf("key", "match")) {
            try {
                redis(arrayOf("GRAPH.QUERY", graphKey(projectId), "CREATE INDEX FOR (n:Entity) ON (n.$property)"))
            } catch (error: RedisError) {
                if ("already indexed" !in error.message.orEmpty()) throw error
            }
        }
        indexed += projectId
    }

    /** The rows of a query's reply. A graph that doesn't exist yet simply has no rows. */
    private fun query(projectId: Long, text: String, parameters: Map<String, Any> = emptyMap()): List<List<Any?>> {
        val reply = try {
            redis(arrayOf("GRAPH.QUERY", graphKey(projectId), Cypher.query(text, parameters)))
        } catch (error: RedisError) {
            if ("empty key" in error.message.orEmpty()) return emptyList()
            throw error
        }
        return rowsOf(reply)
    }

    private fun List<Any?>.toNode() = GraphNode(this[0] as String, this[1] as String, this[2] as String)

    private fun graphKey(projectId: Long) = "project:$projectId"

    companion object {
        const val MAX_HOPS = 4

        /** A reply is [header, rows, statistics] when the query returns something, or just [statistics] when it doesn't. */
        @Suppress("UNCHECKED_CAST")
        fun rowsOf(reply: Any?): List<List<Any?>> {
            val parts = reply as List<*>
            return if (parts.size == 3) parts[1] as List<List<Any?>> else emptyList()
        }
    }
}
