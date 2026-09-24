package ai.rever.boss.app

import ai.rever.boss.components.workspaces.LAST_SESSION_ID
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.window.Project
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A person asked to open [project] from somewhere in window [windowId]. */
internal data class ProjectOpenRequest(
    val windowId: String,
    val project: Project,
)

/**
 * Every "open this project" a person asks for in the host UI, routed to ONE dialog per window.
 *
 * A Space carries its own project, so opening a project is a question about which Space it goes
 * in: this one, a new one, or a new window. The top bar, Home's recent projects, File > Open
 * Project, Clone and New Project each used to answer part of that themselves - some asked
 * "current window or new window" only when a project was already open, some selected straight
 * away - and the project-selection effect then raised a second prompt asking for a layout. They
 * all post here now, and `BossAppDialogs` asks the one question.
 *
 * A plugin selecting a project through `ProjectDataProvider` does not come through here: that is
 * a program, not a person, and it keeps the configured default-Space behaviour.
 */
internal object ProjectOpenRequests {
    private val _requests = MutableSharedFlow<ProjectOpenRequest>(extraBufferCapacity = 8)
    val requests: SharedFlow<ProjectOpenRequest> = _requests.asSharedFlow()

    /**
     * Ask where [project] should open. False when there is no window to ask in, and the caller
     * should then select the project directly, the way it did before this existed.
     */
    fun ask(
        windowId: String?,
        project: Project,
    ): Boolean = windowId != null && _requests.tryEmit(ProjectOpenRequest(windowId, project))
}

/**
 * The Spaces "New Space" offers for a project at [projectPath].
 *
 * Applying a saved Space also selects the project it was saved with (`applyWorkspace` with
 * `restoreProject`), so offering a Space of ANOTHER project would open it and quietly swap out the
 * project the user just chose. Left in: the templates, which become a Space for this project;
 * saved Spaces with no project; and saved Spaces of this same project. Last Session is left out -
 * it is the autosave slot, not a Space anyone picks.
 */
internal fun spacesForProject(
    workspaces: List<LayoutWorkspace>,
    projectPath: String,
): List<LayoutWorkspace> =
    workspaces.filter { workspace ->
        val ownPath = workspace.projectPath.orEmpty()
        workspace.id != LAST_SESSION_ID &&
            (
                workspace.id in PredefinedWorkspaces.allIds ||
                    ownPath.isEmpty() ||
                    samePath(ownPath, projectPath)
            )
    }

private fun samePath(
    a: String,
    b: String,
): Boolean = a.trimEnd('/', '\\') == b.trimEnd('/', '\\')
