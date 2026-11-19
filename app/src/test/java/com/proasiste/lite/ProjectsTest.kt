package com.proasiste.lite

import com.proasiste.lite.ui.ProjectKind
import com.proasiste.lite.ui.UiProject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Saved projects read with org.json, which on the JVM needs Robolectric. */
@RunWith(RobolectricTestRunner::class)
class ProjectsTest {
    @Test fun goalAndDueSurviveSaving() {
        val projects = listOf(UiProject("P01", "Appeal", goal = "File the appeal", due = "2026-12-15"), UiProject("D01", "Tax"))
        assertEquals(projects, PlantroomModel.projectsFromJson(PlantroomModel.projectsToJson(projects)))
    }

    @Test fun oldSavedProjectsHaveNoGoalOrDue() {
        val p = PlantroomModel.projectsFromJson("""[{"id":"P04","name":"Legal","incognito":false}]""")!!.single()
        assertEquals("" to "", p.goal to p.due)
    }

    @Test fun projectsComeFirstThenDomainsResourcesArchives() {
        val ids = listOf("A01", "R02", "P10", "D01", "P02", "R01").map { UiProject(it, it) }.sortedWith(ProjectKind.ORDER).map { it.id }
        assertEquals(listOf("P02", "P10", "D01", "R01", "R02", "A01"), ids)
        assertEquals(ProjectKind.ARCHIVE, ProjectKind.of("a03"))
    }
}
