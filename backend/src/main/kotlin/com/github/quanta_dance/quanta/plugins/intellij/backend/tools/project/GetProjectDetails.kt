// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.project

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.project.ProjectVersionUtil
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.eclipse.jgit.ignore.IgnoreNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

@JsonClassDescription("Provide Project Details and a bounded, depth-first project structure with clear truncation indicators.")
/**
 * Backend tool that summarizes project structure, environment, and an optionally truncated tree.
 *
 * It is intended as the safest first-step discovery tool for new sessions before agents start
 * reading or modifying individual files.
 */
class GetProjectDetails : ToolInterface<String> {
    // Defaults chosen to keep output readable while allowing full small projects
    @field:JsonPropertyDescription("Include a truncated project tree in the summary. Default: false")
    var includeTree: Boolean = false

    @field:JsonPropertyDescription("Maximum number of lines to output for the tree. Default: 500 (hard cap)")
    var maxEntries: Int = 500

    @field:JsonPropertyDescription(
        "Maximum total characters for the tree output. Default: 10,000",
    )
    var maxChars: Int = 10000

    @field:JsonPropertyDescription(
        "Maximum depth to traverse (root’s direct children are depth 1). Default: 12.\n" +
                "Adaptive behavior: for JVM projects (Java/Kotlin/Scala with src/main/java|kotlin|scala), " +
                "an effective depth of at least 32 is used to accommodate deep package structures.",
    )
    var maxDepth: Int = 12

    companion object {
        private val logger = Logger.getInstance(GetProjectDetails::class.java)
    }

    private var gitIgnoreMatcher: GitIgnoreMatcher? = null
    private var gitIgnoreRootPath: String? = null

    private class GitIgnoreMatcher(
        private val rootPath: Path,
    ) {
        private val nodesByDir: ConcurrentHashMap<Path, IgnoreNode?> = ConcurrentHashMap()

        fun isIgnored(vf: VirtualFile): Boolean {
            val filePath =
                try {
                    Paths.get(vf.path).normalize()
                } catch (_: Throwable) {
                    return false
                }

            if (!filePath.startsWith(rootPath)) return false

            val ancestors = mutableListOf<Path>()
            var dir: Path? = filePath.parent
            while (dir != null && dir.startsWith(rootPath)) {
                ancestors.add(dir)
                if (dir == rootPath) break
                dir = dir.parent
            }
            ancestors.reverse()

            var ignored: Boolean? = null
            for (ancestorDir in ancestors) {
                val node = loadIgnoreNode(ancestorDir) ?: continue
                val rel =
                    try {
                        ancestorDir.relativize(filePath).toString().replace('\\', '/')
                    } catch (_: Throwable) {
                        continue
                    }

                when (node.isIgnored(rel, vf.isDirectory)) {
                    IgnoreNode.MatchResult.IGNORED -> ignored = true
                    IgnoreNode.MatchResult.NOT_IGNORED -> ignored = false
                    IgnoreNode.MatchResult.CHECK_PARENT -> {
                        // no-op
                    }

                    else -> {
                        // Newer JGit versions add additional states; we treat them as neutral.
                    }
                }
            }

            return ignored == true
        }

        private fun loadIgnoreNode(dir: Path): IgnoreNode? {
            return nodesByDir.computeIfAbsent(dir) { d ->
                val ignoreFile = d.resolve(".gitignore")
                if (!Files.isRegularFile(ignoreFile)) return@computeIfAbsent null

                try {
                    Files.newInputStream(ignoreFile).use { input ->
                        val node = IgnoreNode()
                        node.parse(input)
                        node
                    }
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    override fun execute(project: Project): String {
        QDLog.info(
            logger,
            { "maxDepth: $maxDepth, maxEntries: $maxEntries, maxChars: $maxChars, includeTree: $includeTree" },
        )
        val sdkVersion =
            try {
                ProjectVersionUtil.getProjectCompileVersion(project)
            } catch (e: Throwable) {
                QDLog.warn(logger, { "Can't get project SDK" }, e)
                null
            }
        val buildFiles =
            try {
                ProjectVersionUtil.getProjectBuildFiles(project)
            } catch (e: Throwable) {
                QDLog.warn(logger, { "Can't get project build files" }, e)
                null
            }

        val basePath = PathUtils.projectRootPath(project)
        if (basePath != null && basePath != gitIgnoreRootPath) {
            gitIgnoreRootPath = basePath
            gitIgnoreMatcher =
                try {
                    GitIgnoreMatcher(Paths.get(basePath).normalize())
                } catch (_: Throwable) {
                    null
                }
        }

        val filesCount =
            if (basePath != null) {
                val root = LocalFileSystem.getInstance().findFileByPath(basePath)
                if (root != null) totalFilesCount(project, root) else 0
            } else {
                0
            }

        val summaryHeader =
            StringBuilder()
                .append("Available build files: ").append(buildFiles)
                .append("\n").append(sdkVersion)
                .append("\nFiles in the project: ").append(filesCount)

        if (!includeTree || basePath == null) {
            return summaryHeader.append("\n(Tree omitted; set includeTree=true to include a truncated listing)")
                .toString()
        }

        val root =
            LocalFileSystem.getInstance().findFileByPath(basePath)
                ?: return summaryHeader.append("\n(Tree unavailable)").toString()

        val effectiveDepth = computeEffectiveMaxDepth(project, root, maxDepth)
        val treeOut =
            try {
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
    private fun computeEffectiveMaxDepth(
        project: Project,
        root: VirtualFile,
        configured: Int,
    ): Int {
        // If the caller set an obviously high depth, keep it
        if (configured >= 32) return configured
        return if (looksJvmLike(root)) maxOf(32, configured) else configured
    }

    private fun looksJvmLike(root: VirtualFile): Boolean {
        fun findChildDir(
            parent: VirtualFile?,
            name: String,
        ): VirtualFile? =
            try {
                parent?.children?.firstOrNull {
                    it.isValid && it.isDirectory &&
                            it.name.equals(
                                name,
                                ignoreCase = false,
                            )
                }
            } catch (_: Throwable) {
                null
            }

        val src = findChildDir(root, "src") ?: return false
        val main = findChildDir(src, "main")
        val test = findChildDir(src, "test")

        fun hasJvmLangDir(base: VirtualFile?): Boolean {
            if (base == null) return false
            return (findChildDir(base, "java") != null) || (findChildDir(base, "kotlin") != null) || (
                    findChildDir(
                        base,
                        "scala",
                    ) != null
                    )
        }
        return hasJvmLangDir(main) || hasJvmLangDir(test)
    }

    // Uses JGit to evaluate .gitignore files (including nested ones) and adds a small performance-focused fallback list.
    private fun isIgnored(vf: VirtualFile): Boolean {
        if (!vf.isValid) return true

        val name = vf.name
        if (name == ".DS_Store") return true

        val quickIgnoredDirs =
            setOf(
                ".git",
                ".hg",
                ".svn",
                ".idea",
                ".gradle",
                "build",
                "out",
                "target",
                "node_modules",
            )

        val path = vf.path.replace('\\', '/')
        for (dir in quickIgnoredDirs) {
            if (path == dir || path.endsWith("/$dir") || path.contains("/$dir/")) return true
        }

        val matcher = gitIgnoreMatcher ?: return false
        return matcher.isIgnored(vf)
    }

    // Count non-ignored files (not directories) in the project tree
    private fun totalFilesCount(
        project: Project,
        root: VirtualFile,
    ): Int {
        var count = 0

        fun dfs(v: VirtualFile) {
            if (isIgnored(v)) return
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
        maxDepth: Int,
    ): String {
        val sb = StringBuilder()
        var entries = 0
        var truncated = false

        fun canAppend(line: String): Boolean = (entries < maxEntries) && (sb.length + line.length <= maxChars)

        fun appendLine(
            depth: Int,
            name: String,
        ): Boolean {
            val indent = "  ".repeat(depth)
            val line = "$indent$name\n"
            return if (canAppend(line)) {
                sb.append(line)
                entries++
                true
            } else {
                false
            }
        }

        fun listChildren(dir: VirtualFile): List<VirtualFile> {
            return try {
                dir.children?.filter { it.isValid && !isIgnored(it) }?.sortedWith(
                    compareBy<VirtualFile>({ !it.isDirectory }, { it.name.lowercase() }),
                ).orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }
        }

        fun dfsDir(
            dir: VirtualFile,
            depth: Int,
        ) {
            if (isIgnored(dir)) return
            // Check the next level (children level) against maxDepth
            if (depth + 1 > maxDepth) {
                val hidden =
                    try {
                        dir.children?.count { it.isValid && !isIgnored(it) } ?: 0
                    } catch (_: Throwable) {
                        0
                    }
                if (hidden > 0) {
                    if (!appendLine(depth + 1, "... (depth limit, +$hidden more)")) truncated = true
                }
                return
            }

            val children = listChildren(dir)

            var shown = 0
            for (child in children) {
                val display = if (child.isDirectory) "${child.name}/" else child.name
                if (!appendLine(depth + 1, display)) {
                    truncated = true
                    return
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
        val top = listChildren(root)
        for (child in top) {
            val display = if (child.isDirectory) "${child.name}/" else child.name
            if (!appendLine(1, display)) {
                truncated = true
                break
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
