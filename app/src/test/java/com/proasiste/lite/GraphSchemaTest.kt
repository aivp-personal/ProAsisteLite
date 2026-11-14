package com.proasiste.lite

import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.graph.GraphSchema
import com.proasiste.lite.graph.LinkType
import com.proasiste.lite.graph.NewFact
import com.proasiste.lite.graph.NodeType
import com.proasiste.lite.runtime.DeepSeekFacts
import com.proasiste.lite.runtime.LegalFacts
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GraphSchemaTest {
    private val contracts = GraphSchema(GraphSchema.CUSTOM,
        listOf(NodeType("Contrato", "Un contrato"), NodeType("Parte", "Una parte"), NodeType("Monto", "Una suma de dinero")),
        listOf(LinkType("FIRMA", "La parte firma el contrato", setOf("Parte"), setOf("Contrato")), LinkType("PAGA", "", setOf("Parte"), setOf("Monto"))))
    private fun fact(st: String, rel: String, ot: String) = NewFact(2, st, "Acme S.A.", rel, ot, "Contrato de arriendo", "Acme S.A. firma el contrato de arriendo.")

    @Test fun customFactsMustFitTheTypesAndTheirDirection() {
        assertNull(contracts.problem(fact("Parte", "FIRMA", "Contrato")))
        assertNotNull(contracts.problem(fact("Contrato", "FIRMA", "Parte")))  // wrong way round
        assertNotNull(contracts.problem(fact("Persona", "FIRMA", "Contrato"))) // unknown type
        assertNotNull(contracts.problem(fact("Parte", "ARRIENDA", "Contrato"))) // unknown link
        assertNull(GraphSchema().problem(fact("Cualquiera", "LO_QUE_SEA", "Otro"))) // automatic leaves facts to its own checks
    }

    @Test fun schemasSurviveJsonAndBadJsonIsAutomatic() {
        assertEquals(contracts, GraphSchema.fromJson(contracts.toJson()))
        assertEquals(GraphSchema(), GraphSchema.fromJson("not json"))
        assertTrue(contracts.complete())
        assertFalse(contracts.copy(links = emptyList()).complete())
        assertFalse(contracts.copy(links = listOf(LinkType("X", "", setOf("Nadie"), setOf("Parte")))).complete())
        assertEquals("FIRMA_CONTRATO", GraphSchema.cleanLink(" firma contrato "))
    }

    @Test fun theLegalPresetIsTheLegalModesTypes() {
        val p = GraphSchema.legalPreset()
        assertTrue(p.custom && p.complete())
        assertEquals(LegalFacts.TYPES, p.nodes.map { it.name })
        assertEquals(LegalFacts.RELATIONS.keys.toList(), p.links.map { it.name })
        assertEquals(setOf("Ministro") to setOf("Fallo"), p.links.first { it.name == "INTEGRA" }.let { it.from to it.to })
        assertNull(p.problem(NewFact(9, "Ministro", "Marisol Peña Torres", "INTEGRA", "Fallo", "STC Rol 1.810", "Marisol Peña Torres integró el Tribunal.")))
    }

    @Test fun theRequestListsTheTypesAsClosedLists() {
        val text = DeepSeekFacts.customInstructions(contracts)
        assertTrue(text.contains("- Contrato: Un contrato")); assertTrue(text.contains("- FIRMA (Parte -> Contrato): La parte firma el contrato"))
        assertFalse(text.contains("UPPER_SNAKE_CASE"))
        val item = DeepSeekFacts.customSchema(contracts).getJSONObject("properties").getJSONObject("facts").getJSONObject("items").getJSONObject("properties")
        assertEquals("[\"Contrato\",\"Parte\",\"Monto\"]", item.getJSONObject("from_type").getJSONArray("enum").toString())
        assertEquals("[\"FIRMA\",\"PAGA\"]", item.getJSONObject("relation").getJSONArray("enum").toString())
    }

    @Test fun customExtractionKeepsOnlyFactsThatFit() = runBlocking {
        // A canned model reply: one fact that fits, one running the wrong way, one with an unknown link.
        val reply = """{"facts":[
            {"page":2,"from_type":"Parte","from":"Acme S.A.","relation":"FIRMA","to_type":"Contrato","to":"Contrato de arriendo","statement":"Acme S.A. firma el contrato de arriendo."},
            {"page":2,"from_type":"Contrato","from":"Contrato de arriendo","relation":"FIRMA","to_type":"Parte","to":"Acme S.A.","statement":"El contrato de arriendo firma a Acme S.A."},
            {"page":2,"from_type":"Parte","from":"Acme S.A.","relation":"ARRIENDA","to_type":"Contrato","to":"Contrato de arriendo","statement":"Acme S.A. arrienda según el contrato de arriendo."}]}"""
        var sent: JSONObject? = null
        val extractor = DeepSeekFacts { _, body -> sent = body; JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", reply)).put("finish_reason", "stop"))) }
        val page = DeepSeekFacts.PageText(2, "Acme S.A. firma el contrato de arriendo con Beta Ltda. Acme S.A. arrienda el local según el contrato de arriendo.")
        val result = extractor.extract("key", "contrato.pdf", listOf(page), schema = contracts)
        assertEquals(listOf("FIRMA"), result.facts.map { it.relation })
        assertEquals(2, result.dropped)
        assertTrue(result.extractor.contains("custom-"))
        assertTrue(sent!!.getJSONArray("messages").getJSONObject(0).getString("content").contains("- FIRMA (Parte -> Contrato)"))
    }

    @Test fun schemasAreSavedPerProjectAndGoWithIt() {
        Store(ApplicationProvider.getApplicationContext(), null).use { store ->
            assertEquals(GraphSchema(), store.graphSchema("P05"))
            store.saveGraphSchema("P05", contracts)
            assertEquals(contracts, store.graphSchema("P05"))
            assertEquals(GraphSchema(), store.graphSchema("P06"))
            store.deleteProject("P05")
            assertEquals(GraphSchema(), store.graphSchema("P05"))
        }
    }
}
