package com.proasiste.lite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.proasiste.lite.graph.Graph
import com.proasiste.lite.graph.NewFact
import com.proasiste.lite.reports.Cited
import com.proasiste.lite.reports.Export
import com.proasiste.lite.reports.Report
import com.proasiste.lite.reports.Reports
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ReportsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: Store
    private lateinit var reports: Reports

    @Before fun open() {
        store = Store(context, null); reports = Reports(store)
        val file = File(store.library, "r.pdf").apply { writeText("ruling") }
        store.add(Source("R", file.path, "pdf", "P04", "", "2026-11-14", "STC 2154-11 LEY PENAL EN BLANCO.pdf", false))
    }
    @After fun close() = store.close()

    private val cited = listOf(Cited("R", "STC 2154-11 LEY PENAL EN BLANCO.pdf", 14, "condiciones copulativas que determinan la constitucionalidad", "14:80,400,430,490"))
    private fun answer() = reports.save(Report(0, "P04", "¿Qué exige la ley penal en blanco?", "¿Qué exige la ley penal en blanco?", "Exige describir el núcleo de la conducta [1].", "DeepSeek V4.1 Flash", cited, 0, 0))

    @Test fun aReportKeepsItsQuestionSourcesAndPlaces() {
        val id = answer()
        val r = reports.get(id)!!
        assertEquals("DeepSeek V4.1 Flash", r.model); assertEquals(cited, r.cited)
        // Editing keeps the creation date and moves the change date.
        Thread.sleep(5)
        reports.save(r.copy(title = "Ley penal en blanco", body = "Texto corregido."))
        val edited = reports.get(id)!!
        assertEquals("Ley penal en blanco", edited.title); assertEquals(r.created, edited.created); assertTrue(edited.updated >= r.updated)
        assertEquals(listOf(id), reports.list("P04").map { it.id }); assertTrue(reports.list("P05").isEmpty())
    }

    @Test fun decisionsKeepTheirHistoryAndTheLatestIsCurrent() {
        val id = answer().toString()
        reports.decide("P04", Reports.REPORT, id, "por revisar")
        Thread.sleep(5)
        reports.decide("P04", Reports.REPORT, id, "aceptado", "Coincide con el considerando 9°")
        reports.decide("P04", Reports.FILE, "R", "relevante")
        assertEquals("aceptado", reports.latest("P04")["${Reports.REPORT}:$id"]!!.value)
        assertEquals(listOf("relevante", "aceptado", "por revisar"), reports.decisions("P04").map { it.value })
        assertEquals("STC 2154-11 LEY PENAL EN BLANCO.pdf", reports.decisions("P04").first { it.target == Reports.FILE }.targetTitle)
    }

    @Test fun deletingTakesItsDecisionsAlong() {
        val id = answer()
        reports.decide("P04", Reports.REPORT, id.toString(), "aceptado"); reports.decide("P04", Reports.FILE, "R", "relevante")
        reports.delete(id)
        assertNull(reports.get(id)); assertEquals(listOf(Reports.FILE), reports.decisions("P04").map { it.target })
        store.deleteSource("R")
        assertTrue(reports.decisions("P04").isEmpty())
        answer(); store.deleteProject("P04")
        assertTrue(reports.list("P04").isEmpty())
    }

    @Test fun markdownHasTheQuestionSourcesAndDecisions() {
        val id = answer(); reports.decide("P04", Reports.REPORT, id.toString(), "aceptado", "Revisado")
        val md = Export.markdown(reports.get(id)!!, reports.decisions("P04"))
        assertTrue(md.startsWith("# ¿Qué exige la ley penal en blanco?"))
        assertTrue(md.contains("**Pregunta:** ¿Qué exige"))
        assertTrue(md.contains("Escrito por DeepSeek V4.1 Flash"))
        assertTrue(md.contains("1. STC 2154-11 LEY PENAL EN BLANCO.pdf, página 14: «condiciones copulativas"))
        assertTrue(md.contains("**aceptado** — Revisado"))
    }

    @Test fun csvQuotesWhatNeedsIt() {
        assertEquals("simple", Export.csv("simple"))
        assertEquals("\"a, b\"", Export.csv("a, b"))
        assertEquals("\"dijo \"\"no\"\"\"", Export.csv("dijo \"no\""))
        reports.decide("P04", Reports.FILE, "R", "relevante", "línea uno\nlínea dos")
        val csv = Export.decisionsCsv(reports.decisions("P04"))
        assertTrue(csv.startsWith("﻿fecha,tipo,titulo,decision,nota\r\n"))
        assertTrue(csv.contains(",archivo,STC 2154-11 LEY PENAL EN BLANCO.pdf,relevante,\"línea uno\nlínea dos\"\r\n"))
    }

    @Test fun exportedGraphImportsBack() {
        val graph = Graph(store)
        fun embed(t: String) = FloatArray(Store.DIMENSIONS) { if (it == t.length % Store.DIMENSIONS) 1f else 0.001f }
        graph.replaceFacts("P04", "R", listOf(NewFact(14, "Fallo", "STC Rol 2.154", "RAZONA", "Concepto", "Ley penal en blanco", "La ley penal en blanco debe describir el núcleo.", "deepseek/x", "14:80,400,430,490")), ::embed)
        val facts = Export.facts(store, "P04")
        assertTrue(Export.factsCsv(facts).contains("STC 2154-11 LEY PENAL EN BLANCO.pdf,14,Fallo,STC Rol 2.154,RAZONA,Concepto,Ley penal en blanco"))
        val files = JSONArray(Export.factsJson(facts))
        assertEquals(1, files.length())
        graph.replaceFacts("P04", "R", emptyList(), ::embed)
        assertEquals(1, graph.importFile("P04", files.getJSONObject(0).toString(), ::embed))
        assertEquals("La ley penal en blanco debe describir el núcleo.", graph.pageItems("P04", "R", 14).single().fact.statement)
    }

    @Test fun fileNamesAreSafe() {
        assertEquals("Qué exige la ley penal en blanco.pdf", Export.fileName("¿Qué exige la ley penal en blanco?", "pdf"))
        assertEquals("informe.md", Export.fileName("???", "md"))
        // An accent stored as a separate mark stays on its letter.
        assertEquals("STC 2301-12 RETENCIÓN.doclang", Export.fileName("STC 2301-12 RETENCIÓN", "doclang"))
    }

    @Test fun schemaEightUpgrades() {
        val name = "reports-upgrade.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            Store.MIGRATIONS.take(8).flatten().forEach(db::execSQL); db.version = 8
        }
        Store(context, name).use { s -> assertTrue(Reports(s).list("P01").isEmpty()); assertTrue(Reports(s).decide("P01", Reports.FILE, "X", "relevante") > 0) }
        context.deleteDatabase(name)
    }
}
