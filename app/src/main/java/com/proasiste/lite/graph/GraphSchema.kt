package com.proasiste.lite.graph

import com.proasiste.lite.runtime.LegalFacts
import org.json.JSONArray
import org.json.JSONObject

/** A kind of node the graph may hold, with what it means. */
data class NodeType(val name: String, val description: String = "")
/** A kind of link, with what it means and the node types it may run from and to. */
data class LinkType(val name: String, val description: String = "", val from: Set<String>, val to: Set<String>)

/**
 * What a project's graph extracts. [AUTO] is the app's own behaviour: Chilean court rulings are read in legal mode (the
 * rule-based skeleton, then two passes with fixed types), other files get free-form facts. [CUSTOM] uses [nodes] and
 * [links] for every file: the model may only use those names, and a link must run between the types it allows.
 */
data class GraphSchema(val mode: String = AUTO, val nodes: List<NodeType> = emptyList(), val links: List<LinkType> = emptyList()) {
    val custom: Boolean get() = mode == CUSTOM

    /** Why [f] breaks the schema, or null when it fits. Automatic mode leaves facts to its own checks. */
    fun problem(f: NewFact): String? {
        if (!custom) return null
        val names = nodes.map { it.name }.toSet()
        if (f.subjectType !in names) return "type ${f.subjectType} is not in the project's types"
        if (f.objectType !in names) return "type ${f.objectType} is not in the project's types"
        val link = links.firstOrNull { it.name == f.relation } ?: return "link ${f.relation} is not in the project's links"
        if ((link.from.isNotEmpty() && f.subjectType !in link.from) || (link.to.isNotEmpty() && f.objectType !in link.to)) return "link ${f.relation} doesn't run ${f.subjectType} -> ${f.objectType}"
        return null
    }

    /** A schema can be used to extract only with at least one type and one link, and links naming known types. */
    fun complete(): Boolean = !custom || (nodes.isNotEmpty() && links.isNotEmpty() && links.all { l -> (l.from + l.to).all { t -> nodes.any { it.name == t } } })

    /** Short version string recorded with each fact, so a fact can be read against the schema that produced it. */
    val version: String get() = if (!custom) mode else "custom-" + Integer.toHexString(toJson().hashCode())

    fun toJson(): String {
        val n = JSONArray(nodes.map { JSONObject().put("name", it.name).put("description", it.description) })
        val l = JSONArray(links.map { JSONObject().put("name", it.name).put("description", it.description).put("from", JSONArray(it.from.sorted())).put("to", JSONArray(it.to.sorted())) })
        return JSONObject().put("mode", mode).put("nodes", n).put("links", l).toString()
    }

    companion object {
        const val AUTO = "auto"; const val CUSTOM = "custom"

        fun fromJson(json: String): GraphSchema = runCatching {
            val o = JSONObject(json)
            fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet()
            val nodes = o.optJSONArray("nodes")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { n -> NodeType(n.getString("name"), n.optString("description")) } } }.orEmpty()
            val links = o.optJSONArray("links")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { l -> LinkType(l.getString("name"), l.optString("description"), strings(l.optJSONArray("from")), strings(l.optJSONArray("to"))) } } }.orEmpty()
            GraphSchema(o.optString("mode", AUTO), nodes, links)
        }.getOrDefault(GraphSchema())

        /** A type or link name as the model will see it: one line, no surrounding space; links in UPPER_SNAKE_CASE. */
        fun cleanType(name: String) = name.replace(Regex("\\s+"), " ").trim().take(40)
        fun cleanLink(name: String) = name.trim().uppercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40)

        /** The legal mode's own types and links, as a custom schema to start from and edit. */
        fun legalPreset(): GraphSchema {
            val described = mapOf(
                "Fallo" to "Una sentencia o resolución de un tribunal, por su rol (STC Rol 1.810).",
                "Norma" to "Una ley, artículo, inciso o tratado (Ley N° 18.933, art. 38).",
                "Tribunal" to "Un tribunal o sala.", "Ministro" to "Un ministro o ministra del tribunal.",
                "Parte" to "Una persona, empresa u órgano que es parte en la causa.", "Abogado" to "Un abogado que alega.",
                "Gestion" to "La causa pendiente en que incide el requerimiento.", "Concepto" to "Una idea, argumento, petición o institución.",
                "Voto" to "Una disidencia o prevención.", "Considerando" to "Un considerando numerado del fallo.",
            )
            val nodes = LegalFacts.TYPES.map { NodeType(it, described[it].orEmpty()) }
            val links = LegalFacts.RELATIONS.map { (name, description) ->
                val (from, to) = LegalFacts.DIRECTIONS.getValue(name)
                LinkType(name, description.substringAfter(": ", description), from, to)
            }
            return GraphSchema(CUSTOM, nodes, links)
        }
    }
}
