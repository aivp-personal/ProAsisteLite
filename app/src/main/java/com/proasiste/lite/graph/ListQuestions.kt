package com.proasiste.lite.graph

/**
 * Recognises a question that asks for a whole list the graph holds, such as "qué fallos citó el tribunal", and says
 * which relations answer it. These go straight to the graph: picking the few facts most like the question would
 * leave items out. A question it doesn't recognise returns nothing and takes the normal search.
 */
object ListQuestions {
    fun relations(question: String): Set<String> {
        val words = Graph.keyOf(question).split(" ")
        fun has(vararg starts: String) = words.any { word -> starts.any(word::startsWith) }

        val aboutRulings = has("fallo", "sentencia", "precedent", "jurisprudencia", "roles", "ruling", "case")
        val citing = has("cit", "invoc", "recog", "precedent", "jurisprudencia")
        val aboutNorms = has("norma", "articulo", "ley", "precepto", "disposicion", "law", "provision")
        return when {
            has("disid", "disint", "disent", "dissent") -> setOf("DISIENTE")
            has("previn", "prevencion") -> setOf("PREVIENE")
            has("redact", "drafted", "wrote") -> setOf("REDACTA")
            aboutRulings && citing -> {
                val parties = has("parte", "requirente", "concejal", "requerid", "party", "parties")
                val court = has("tribunal", "magistratura", "sentenciador", "court")
                when {
                    parties && !court -> setOf("INVOCA_PRECEDENTE")
                    court && !parties -> setOf("CITA_PRECEDENTE")
                    else -> setOf("CITA_PRECEDENTE", "INVOCA_PRECEDENTE")
                }
            }
            aboutNorms && has("impugn", "challeng") -> setOf("IMPUGNA")
            aboutNorms && has("vulner", "violat") -> setOf("INVOCA_VULNERACION")
            aboutNorms && has("fund", "apoy", "relied", "based") -> setOf("FUNDAMENTA_EN")
            has("resolv", "resuelv", "decidi", "decided", "ruled") -> setOf("RESUELVE")
            has("tramit") -> setOf("TRAMITA")
            has("ministr", "integr", "justice", "judge") -> setOf("INTEGRA")
            else -> emptySet()
        }
    }
}
