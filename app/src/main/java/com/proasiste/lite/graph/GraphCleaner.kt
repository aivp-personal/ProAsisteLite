package com.proasiste.lite.graph

import com.proasiste.lite.data.Library
import java.io.IOException

/**
 * Applies deletions to the graph. SQLite and FalkorDB can't share a transaction, so a delete queues its graph cleanup in the
 * same SQLite transaction, and this works through the queue. If the server is down, entries wait until it's back.
 */
class GraphCleaner(private val library: Library, private val graph: GraphStore) {

    /** Returns true when the queue is empty. */
    fun flush(): Boolean {
        for (cleanup in library.pendingGraphCleanups()) {
            try {
                if (cleanup.documentId != null) {
                    graph.removeDocument(cleanup.projectId, cleanup.documentId)
                } else {
                    graph.removeProject(cleanup.projectId)
                }
            } catch (unavailable: IOException) {
                return false
            }
            library.finishGraphCleanup(cleanup.id)
        }
        return true
    }
}
