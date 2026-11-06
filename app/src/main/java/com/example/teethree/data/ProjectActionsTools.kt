package com.example.teethree.data

import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ProjectActionLaunch(
    val threadId: String,
    val terminalId: String,
    val cwd: String,
    val worktreePath: String?,
    val env: JsonObject,
    val initialInput: String,
)

class ProjectActionLaunchFailure(val terminalId: String, cause: Throwable) :
    IOException("The action may have started. Inspect its terminal before running it again.", cause)

internal fun projectActionLaunch(
    project: T3Project,
    thread: T3Thread,
    action: ProjectAction,
): ProjectActionLaunch {
    require(thread.projectId == project.id && !thread.archived && !project.archived) {
        "Choose an active conversation in this project."
    }
    require(
        project.workspaceRoot.isNotBlank() &&
            project.workspaceRoot.length <= 8192 &&
            (thread.worktreePath?.length ?: 0) <= 8192
    ) {
        "The project workspace is unavailable."
    }
    require(action.command.isNotBlank()) { "This action has no command." }
    return ProjectActionLaunch(
        thread.id,
        "action-${newId()}",
        thread.worktreePath ?: project.workspaceRoot,
        thread.worktreePath,
        projectTerminalEnv(project.workspaceRoot, thread.worktreePath),
        " ${action.command}\r",
    )
}

internal fun projectTerminalEnv(projectRoot: String, worktreePath: String?) = buildJsonObject {
    put("T3CODE_PROJECT_ROOT", projectRoot)
    worktreePath?.let { put("T3CODE_WORKTREE_PATH", it) }
}

class ProjectActionsTools(private val repository: T3Repository) {
    fun actions(thread: T3Thread): List<ProjectAction> {
        val project =
            repository.state.value.projects.firstOrNull { it.id == thread.projectId }
                ?: return emptyList()
        return effectiveProjectScripts(
                repository.state.value.serverConfig?.obj("settings") ?: JsonObject(emptyMap()),
                project,
            )
            .filterIsInstance<JsonObject>()
            .mapNotNull(::parseProjectAction)
    }

    suspend fun run(thread: T3Thread, action: ProjectAction): String {
        val project =
            repository.state.value.projects.firstOrNull { it.id == thread.projectId }
                ?: error("Project is unavailable.")
        val launch = projectActionLaunch(project, thread, action)
        return repository
            .terminalSession(
                thread.id,
                cwd = project.workspaceRoot,
                worktreePath = thread.worktreePath,
            )
            .use { session -> session.launchProjectAction(launch) }
    }
}
