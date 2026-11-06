package com.example.teethree.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal data class ProjectGroup(val key: String, val title: String, val projects: List<T3Project>)

internal fun groupProjects(projects: List<T3Project>, mode: String): List<ProjectGroup> {
    require(mode in setOf("repository", "repository_path", "separate"))
    fun relativePath(project: T3Project): String? {
        val root = project.repositoryRoot?.replace('\\', '/')?.trimEnd('/') ?: return null
        val path = project.workspaceRoot.replace('\\', '/').trimEnd('/')
        val ignoreCase = root.getOrNull(1) == ':'
        return when {
            path.equals(root, ignoreCase) -> ""
            path.startsWith("$root/", ignoreCase) -> path.substring(root.length + 1)
            else -> null
        }
    }
    return projects
        .filterNot { it.archived }
        .groupBy { project ->
            val repository = project.repositoryKey
            val path = relativePath(project)
            if (
                mode == "separate" ||
                    repository == null ||
                    (mode == "repository_path" && path == null)
            )
                "project:${project.id}"
            else
                JsonArray(
                        listOf(repository, if (mode == "repository_path") path.orEmpty() else "")
                            .map(::JsonPrimitive)
                    )
                    .toString()
        }
        .map { (key, members) ->
            val first = members.first()
            val title = if (members.size > 1) first.repositoryLabel ?: first.title else first.title
            ProjectGroup(key, title, members.sortedByDescending { it.pinned })
        }
        .sortedByDescending { it.projects.any { project -> project.pinned } }
}
