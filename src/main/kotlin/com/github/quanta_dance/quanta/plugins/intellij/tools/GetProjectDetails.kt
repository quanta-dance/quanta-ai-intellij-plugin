package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.project.ProjectVersionUtil.getProjectBuildFiles
import com.github.quanta_dance.quanta.plugins.intellij.project.ProjectVersionUtil.getProjectCompileVersion
import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.FilePath
import git4idea.commands.Git
import git4idea.repo.GitRepositoryManager

@JsonClassDescription("Provide Project Details and a bounded, depth-first project structure with clear truncation indicators.")
class GetProjectDetails : ToolInterface<String> {

    // Defaults chosen to keep output readable while allowing full small projects
    @JsonPropertyDescription("Include a truncated project tree in the summary. Default: false")
    var includeTree: Boolean = false

    @JsonPropertyDescription("Maximum number of lines to output for the tree. Default: 500 (hard cap)")
    var maxEntries: Int = 500

    @JsonPropertyDescription(
        "Maximum total characters for the tree output. Default: 10,000"
    )
    var maxChars: Int = 10000

    @JsonPropertyDescription(
        "Maximum depth to traverse (root’s direct children are depth 1). Default: 12.\n" +
                "Adaptive behavior: for JVM projects (Java/Kotlin/Scala with src/main/java|kotlin|scala), an effective depth of at least 32 is used to accommodate deep package structures."
    )
    var maxDepth: Int = 12

    companion object {
        private val logger = Logger.getInstance(GetProjectDetails::class.java)
    }

    override fun execute(project: Project): String {
        QDLog.info(
            logger,
            { "maxDepth: $maxDepth, maxEntries: $maxEntries, maxChars: $maxChars, includeTree: $includeTree" })
        val sdkVersion = try {
            getProjectCompileVersion(project)
        } catch (e: Throwable) {
            QDLog.warn(logger, { "Can't get project SDK" }, e); null
        }
        val buildFiles = try {
            getProjectBuildFiles(project)
        } catch (e: Throwable) {
            QDLog.warn(logger, { "Can't get project build files" }, e); null
        }

        val basePath = project.basePath
        val filesCount = if (basePath != null) {
            val root = LocalFileSystem.getInstance().findFileByPath(basePath)
            if (root != null) totalFilesCount(project, root) else 0
        } else 0

        project.service<ToolWindowService>().addToolingMessage(
            "Get project Details",
            "Available build files: $buildFiles\n$sdkVersion\nFiles in the project: $filesCount"
        )

        val summaryHeader = StringBuilder()
            .append("Available build files: ").append(buildFiles)
            .append("\n").append(sdkVersion)
            .append("\nFiles in the project: ").append(filesCount)

        if (!includeTree || basePath == null) {
            return summaryHeader.append("\n(Tree omitted; set includeTree=true to include a truncated listing)")
                .toString()
        }

        val root = LocalFileSystem.getInstance().findFileByPath(basePath)
            ?: return summaryHeader.append("\n(Tree unavailable)").toString()

        val effectiveDepth = computeEffectiveMaxDepth(project, root, maxDepth)
        val treeOut = try {
            buildDepthFirstListing(project, root, maxEntries, maxChars, effectiveDepth)
        } catch (e: Throwable) {
            QDLog.warn(logger, { "Error building project tree" }, e)
            "(tree build failed: ${e.message})"
        }

        return summaryHeader
            .append("\nProject structure (depth-first):\n")
            .append(treeOut)
            .toString()
    }

    // Determine if this looks like a JVM project and adjust max depth accordingly
    private fun computeEffectiveMaxDepth(project: Project, root: VirtualFile, configured: Int): Int {
        // If the caller set an obviously high depth, keep it
        if (configured >= 32) return configured
        return if (looksJvmLike(root)) maxOf(32, configured) else configured
    }

    private fun looksJvmLike(root: VirtualFile): Boolean {
        fun findChildDir(parent: VirtualFile?, name: String): VirtualFile? = try {
            parent?.children?.firstOrNull { it.isValid && it.isDirectory && it.name.equals(name, ignoreCase = false) }
        } catch (_: Throwable) {
            null
        }

        val src = findChildDir(root, "src") ?: return false
        val main = findChildDir(src, "main")
        val test = findChildDir(src, "test")
        fun hasJvmLangDir(base: VirtualFile?): Boolean {
            if (base == null) return false
            return (findChildDir(base, "java") != null) || (findChildDir(base, "kotlin") != null) || (findChildDir(
                base,
                "scala"
            ) != null)
        }
        return hasJvmLangDir(main) || hasJvmLangDir(test)
    }

    // Accurate Git-ignore check leveraging Git plugin and VCS manager
    private fun isIgnored(project: Project, vf: VirtualFile): Boolean {
        return try {
            val repo = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(vf)
            if (repo != null) {
                val filePath: FilePath = LocalFilePath(vf.path, vf.isDirectory)
                val ignored: Set<FilePath> = Git.getInstance().ignoredFilePaths(project, repo.root, listOf(filePath))
                if (ignored.contains(filePath)) return true
            }
            val plvm = ProjectLevelVcsManager.getInstance(project)
            if (try {
                    plvm.isIgnored(vf)
                } catch (_: Throwable) {
                    false
                }
            ) return true
            val fp: FilePath = LocalFilePath(vf.path, vf.isDirectory)
            try {
                plvm.isIgnored(fp)
            } catch (_: Throwable) {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    // Count non-ignored files (not directories) in the project tree
    private fun totalFilesCount(project: Project, root: VirtualFile): Int {
        var count = 0
        fun dfs(v: VirtualFile) {
            if (!v.isValid || isIgnored(project, v)) return
            if (v.isDirectory) {
                v.children?.forEach { dfs(it) }
            } else {
                count++
            }
        }
        dfs(root)
        return count
    }

    // Build a grouped, depth-first listing with correct indentation and truncation hints
    private fun buildDepthFirstListing(
        project: Project,
        root: VirtualFile,
        maxEntries: Int,
        maxChars: Int,
        maxDepth: Int
    ): String {
        val sb = StringBuilder()
        var entries = 0
        var truncated = false

        fun canAppend(line: String): Boolean = (entries < maxEntries) && (sb.length + line.length <= maxChars)
        fun appendLine(depth: Int, name: String): Boolean {
            val indent = "  ".repeat(depth)
            val line = "$indent$name\n"
            return if (canAppend(line)) {
                sb.append(line); entries++; true
            } else {
                false
            }
        }

        fun listChildren(project: Project, dir: VirtualFile): List<VirtualFile> {
            return try {
                dir.children?.filter { it.isValid && !isIgnored(project, it) }?.sortedWith(
                    compareBy<VirtualFile>({ !it.isDirectory }, { it.name.lowercase() })
                ).orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }
        }

        fun dfsDir(dir: VirtualFile, depth: Int) {
            if (!dir.isValid || isIgnored(project, dir)) return
            // Check the next level (children level) against maxDepth
            if (depth + 1 > maxDepth) {
                val hidden = try {
                    dir.children?.count { it.isValid && !isIgnored(project, it) } ?: 0
                } catch (_: Throwable) {
                    0
                }
                if (hidden > 0) {
                    if (!appendLine(depth + 1, "... (depth limit, +$hidden more)")) truncated = true
                }
                return
            }

            val children = listChildren(project, dir)
            var shown = 0
            for (child in children) {
                val display = if (child.isDirectory) "${child.name}/" else child.name
                if (!appendLine(depth + 1, display)) {
                    truncated = true; return
                }
                shown++
                if (child.isDirectory) {
                    dfsDir(child, depth + 1)
                    if (truncated) return
                }
            }
            val hidden = children.size - shown
            if (hidden > 0) {
                if (!appendLine(depth + 1, "... (+$hidden more)")) truncated = true
            }
        }

        // We don’t print the root directory itself, only its non-ignored children at depth 1
        val top = listChildren(project, root)
        for (child in top) {
            val display = if (child.isDirectory) "${child.name}/" else child.name
            if (!appendLine(1, display)) {
                truncated = true; break
            }
            if (child.isDirectory) {
                dfsDir(child, 1)
                if (truncated) break
            }
        }

        if (truncated) sb.append("... (truncated)\n")
        return sb.toString().trimEnd()
    }
}
