package dev.rivo.relay

import dev.rivo.relay.codex.*
import dev.rivo.relay.data.ThreadSummary
import dev.rivo.relay.ui.projects.ProjectSummary
import org.junit.Assert.*
import org.junit.Test

class ProjectActivityProjectionTest {
    @Test fun listAndSelectionUseSameNodeActivityAndCompletionClearsBoth() {
        val p = ProjectSummary("p", "Project", "/p")
        val other = ProjectSummary("q", "Other", "/q")
        val task = ThreadSummary("t", "", "", "running", "", "p", nativeExecution = "running")
        val initial = CatalogState(projects = listOf(p, other), selectedProject = p)
        val running = initial.withProjectActivity(NodeActivity(tasks = mapOf("t" to task)))
        assertTrue(running.projects[0].isRunning)
        assertFalse(running.projects[1].isRunning)
        assertTrue(running.selectedProject!!.isRunning)
        val completed = running.withProjectActivity(NodeActivity(confirmed = true, known = setOf("t")))
        assertFalse(completed.projects[0].isRunning)
        assertFalse(completed.selectedProject!!.isRunning)
    }

    @Test fun restoresNewAndLegacyProjectStateWithoutOldStatistics() {
        val expected = ProjectSummary("p", "P", "/p")
        assertEquals(expected, pendingProjectSaver.restore(arrayListOf("p", "P", "/p")))
        assertEquals(expected, pendingProjectSaver.restore(arrayListOf("p", "P", "/p", "9", "2")))
        assertNull(pendingProjectSaver.restore(arrayListOf()))
        assertNull(pendingProjectSaver.restore(arrayListOf("p")))
    }

    @Test fun waitingTaskDoesNotAnimateAsExecuting() {
        val task = ThreadSummary("t", "", "", "waiting", "", "p", nativeExecution = "running")
        val state = CatalogState(projects = listOf(ProjectSummary("p", "P", "/p")))
        assertFalse(state.withProjectActivity(NodeActivity(tasks = mapOf("t" to task))).projects.single().isRunning)
    }
}
