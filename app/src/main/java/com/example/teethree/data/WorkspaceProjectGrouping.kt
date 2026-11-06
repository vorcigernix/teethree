package com.example.teethree.data

import java.time.Instant

internal fun normalizeWorkspacePath(value: String): String {
    val path = value.trim()
    val windows = Regex("^[a-zA-Z]:([/\\\\]|$)").containsMatchIn(path) || path.startsWith("\\\\")
    val trimmed = if (path.startsWith("/")) path.trimEnd('/') else path.trimEnd('/', '\\')
    val normalized = if (windows) trimmed.replace('/', '\\').lowercase() else trimmed
    return when {
        normalized.isEmpty() -> path
        Regex("^[a-z]:$").matches(normalized) -> "$normalized\\"
        else -> normalized
    }
}

internal fun groupWorkspaceProjects(
    environments: List<WorkspaceEnvironmentShell>,
    mode: String,
): List<WorkspaceProjectGroup> {
    require(mode in setOf("repository", "repository_path", "separate"))
    data class Entry(val environment: WorkspaceEnvironmentShell, val project: T3Project) {
        val ref
            get() =
                WorkspaceProjectRef(environment.connectionId, environment.environmentId, project.id)

        val physical
            get() =
                "${environment.environmentId ?: "connection:${environment.connectionId}"}:${normalizeWorkspacePath(project.workspaceRoot)}"
    }
    fun freshness(project: T3Project): Instant = runCatching {
        Instant.parse(project.updatedAt)
    }
        .getOrElse { runCatching { Instant.parse(project.createdAt) }.getOrDefault(Instant.EPOCH) }
    val compare = compareBy<Entry> { freshness(it.project) }.thenBy { it.project.id }
    val members = linkedMapOf<String, MutableList<Entry>>()
    val refs = linkedMapOf<String, MutableList<WorkspaceProjectRef>>()
    environments
        .flatMap { environment -> environment.projects.map { Entry(environment, it) } }
        .groupBy { it.physical }
        .forEach { (physical, versions) ->
            val winner = versions.maxWith(compare)
            val identified =
                if (!winner.project.repositoryKey.isNullOrBlank()) winner
                else
                    versions
                        .filter { !it.project.repositoryKey.isNullOrBlank() }
                        .maxWithOrNull(compare) ?: winner
            val repository =
                identified.project.repositoryKey?.takeUnless {
                    it.isBlank() || it.contains('\u0000')
                }
            val key =
                when {
                    winner.project.isScratch -> "\u0000no-project"
                    mode == "separate" || repository == null -> physical
                    mode == "repository" -> repository
                    else -> {
                        val root = identified.project.repositoryRoot?.let(::normalizeWorkspacePath)
                        val path = normalizeWorkspacePath(identified.project.workspaceRoot)
                        val separator = if (root?.contains('\\') == true) "\\" else "/"
                        val relative =
                            root
                                ?.takeIf { path.startsWith(it + separator) }
                                ?.let { path.removePrefix(it + separator).replace('\\', '/') }
                        if (relative.isNullOrEmpty()) repository else "$repository::$relative"
                    }
                }
            members.getOrPut(key, ::mutableListOf) += winner
            refs.getOrPut(key, ::mutableListOf).addAll(versions.map { it.ref })
        }
    return members
        .map { (key, values) ->
            val representative = values.first()
            val titles =
                values.map { it.project.title.trim() }.filter(String::isNotEmpty).distinct()
            val labels =
                values
                    .mapNotNull { it.project.repositoryLabel?.trim()?.takeIf(String::isNotEmpty) }
                    .distinct()
            val names =
                values
                    .mapNotNull { it.project.repositoryName?.trim()?.takeIf(String::isNotEmpty) }
                    .distinct()
            val label =
                when {
                    representative.project.isScratch -> "No project"
                    values.size == 1 -> representative.project.title
                    titles.size == 1 && titles.single() !in labels && titles.single() !in names ->
                        titles.single()
                    labels.size == 1 -> labels.single()
                    names.size == 1 -> names.single()
                    else -> representative.project.title
                }
            WorkspaceProjectGroup(
                key,
                label,
                representative.ref,
                refs[key].orEmpty().distinct(),
                values.any { it.project.pinned },
                values.all { it.project.archived },
            )
        }
        .sortedByDescending { it.pinned }
}
