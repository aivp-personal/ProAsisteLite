package com.proasiste.lite.graph

/** Builds FalkorDB queries. Values always travel as parameters, so text from a document can't change a query. */
object Cypher {
    fun query(text: String, parameters: Map<String, Any> = emptyMap()): String {
        if (parameters.isEmpty()) return text
        val header = parameters.entries.joinToString(" ") { (name, value) -> "$name=${literal(value)}" }
        return "CYPHER $header $text"
    }

    private fun literal(value: Any): String = when (value) {
        is String -> buildString {
            append('"')
            for (character in value) {
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }
        is Int, is Long -> value.toString()
        else -> throw IllegalArgumentException("Unsupported parameter: ${value.javaClass.simpleName}")
    }
}
