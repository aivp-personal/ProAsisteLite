package com.proasiste.lite.graph

/** [key] identifies a node within its project: the same type and name, ignoring case and accents, is the same node. */
data class GraphNode(val key: String, val type: String, val name: String)

/** Where an edge's claim comes from. An edge can't exist without one. */
data class GraphSource(val documentId: Long, val page: Int)

data class GraphEdge(val fromKey: String, val toKey: String, val type: String, val source: GraphSource)

data class GraphNeighbor(val node: GraphNode, val hops: Int)

/**
 * The app talks to the graph only through this interface. Each project has its own separate graph.
 * A node exists only to be linked: removing a document also removes any node it leaves with no edges.
 */
interface GraphStore {
    fun upsertNode(projectId: Long, type: String, name: String): GraphNode
    fun addEdge(projectId: Long, fromKey: String, toKey: String, type: String, source: GraphSource): GraphEdge
    fun findNodes(projectId: Long, name: String, type: String? = null): List<GraphNode>
    fun edgesOf(projectId: Long, key: String): List<GraphEdge>

    /** Nodes within [maxHops] of [key] in either direction, nearest first. */
    fun neighbors(projectId: Long, key: String, maxHops: Int): List<GraphNeighbor>

    fun removeDocument(projectId: Long, documentId: Long)
    fun removeProject(projectId: Long)
}
