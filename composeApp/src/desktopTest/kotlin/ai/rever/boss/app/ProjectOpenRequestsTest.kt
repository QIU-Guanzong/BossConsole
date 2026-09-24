package ai.rever.boss.app

import ai.rever.boss.components.workspaces.LAST_SESSION_ID
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.window.Project
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Opening a project asks one question - This Space, New Space, or New Window - and "New Space"
 * then lists only the Spaces the project can actually open in.
 */
class ProjectOpenRequestsTest {
    private fun space(
        id: String,
        projectPath: String? = null,
    ) = LayoutWorkspace(
        id = id,
        name = "Space $id",
        description = "d",
        layout = SplitConfig.SinglePanel(PanelConfig(id = "main", tabs = emptyList())),
        projectPath = projectPath,
    )

    @Test
    fun `New Space offers templates, project-free Spaces and this project's, never another project's`() {
        val template = space(PredefinedWorkspaces.CLAUDE_CODE_ID, projectPath = "/elsewhere")
        val free = space("workspace-1")
        val mine = space("workspace-2", projectPath = "/work/app/")
        val theirs = space("workspace-3", projectPath = "/work/other")
        val lastSession = space(LAST_SESSION_ID)

        assertEquals(
            listOf(template, free, mine),
            spacesForProject(listOf(template, free, mine, theirs, lastSession), "/work/app"),
            "a trailing separator is the same directory; another project's Space would swap it out",
        )
    }

    @Test
    fun `a request reaches the window it names`() =
        runBlocking {
            val project = Project(name = "app", path = "/work/app")
            val received =
                launch {
                    assertEquals(ProjectOpenRequest("w1", project), ProjectOpenRequests.requests.first())
                }
            yield()
            ProjectOpenRequests.ask("w1", project)
            received.join()
        }

    @Test
    fun `with no window to ask in, the caller is told to select directly`() {
        assertFalse(ProjectOpenRequests.ask(null, Project(name = "app", path = "/work/app")))
    }
}
