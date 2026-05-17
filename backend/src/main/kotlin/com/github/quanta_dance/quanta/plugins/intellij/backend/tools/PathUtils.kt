// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.eel.provider.utils.EelPathUtils
import java.nio.file.Path
import java.nio.file.Paths

object PathUtils {
    /**
     * Resolve the project root as an actual NIO path.
     * In remote IDE sessions EelPathUtils maps client-side mirrored paths to backend paths.
     */
    @JvmStatic
    fun projectRootPath(project: Project): String? = projectRootNioPath(project)?.toString()

    @JvmStatic
    fun projectRootNioPath(project: Project): Path? {
        val vFilePath =
            projectRootVirtualFile(project)
                ?.let { vf ->
                    try {
                        actualPath(vf.toNioPath())
                    } catch (_: Throwable) {
                        null
                    }
                }
        if (vFilePath != null) return vFilePath
        val basePath = project.basePath?.takeIf { it.isNotBlank() } ?: return null
        return try {
            actualPath(Paths.get(basePath))
        } catch (_: Throwable) {
            Paths.get(basePath).toAbsolutePath().normalize()
        }
    }

    @JvmStatic
    fun projectRootVirtualFile(project: Project): VirtualFile? {
        if (project.isDisposed) return null

        val basePath = project.basePath
        val roots =
            try {
                ProjectRootManager
                    .getInstance(project)
                    .contentRootsFromAllModules
                    .filter { it.isValid }
            } catch (_: Throwable) {
                emptyList()
            }

        val rootCandidates = mutableListOf<VirtualFile>()
        basePath
            ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            ?.takeIf { it.isValid }
            ?.let { rootCandidates += it }
        rootCandidates += roots
        if (rootCandidates.isEmpty()) return null

        val uniqueRoots = rootCandidates.distinctBy { it.path }
        return uniqueRoots.minByOrNull { it.path.length }
    }

    @JvmStatic
    fun resolveVirtualFileWithinProject(
        project: Project,
        relativePath: String?,
        allowBlankAsDot: Boolean = false,
    ): VirtualFile? {
        val root = projectRootVirtualFile(project) ?: return null
        val base = projectRootNioPath(project) ?: return null
        val resolved = resolveWithinProject(project, relativePath, allowBlankAsDot)
        return try {
            val relative = base.relativize(resolved).toString().replace('\\', '/')
            if (relative.isBlank() || relative == ".") root else VfsUtilCore.findRelativeFile(relative, root)
        } catch (_: Throwable) {
            LocalFileSystem.getInstance().findFileByPath(resolved.toString())
                ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(resolved.toString())
        }
    }

    /**
     * Resolve a project-relative path within the project root, normalizing and preventing traversal outside.
     * If allowBlankAsDot is true and relativePath is null/blank, "." is used.
     */
    @JvmStatic
    fun resolveWithinProject(
        projectBase: String,
        relativePath: String?,
        allowBlankAsDot: Boolean = false,
    ): Path {
        val base = actualPath(Paths.get(projectBase).toAbsolutePath().normalize())
        val rel = relativePath?.trim()
        val effective =
            when {
                rel.isNullOrEmpty() && allowBlankAsDot -> "."
                rel.isNullOrEmpty() -> throw IllegalArgumentException("Path is not specified.")
                else -> rel
            }
        val inputPath = Paths.get(effective)
        val resolved =
            if (inputPath.isAbsolute) {
                actualPath(inputPath.toAbsolutePath().normalize())
            } else {
                actualPath(base.resolve(inputPath).normalize())
            }
        if (!resolved.startsWith(base)) {
            throw IllegalArgumentException("Path escapes project root: '$relativePath'")
        }
        return resolved
    }

    @JvmStatic
    fun resolveWithinProject(
        project: Project,
        relativePath: String?,
        allowBlankAsDot: Boolean = false,
    ): Path {
        val base = projectRootNioPath(project) ?: throw IllegalArgumentException("Project root not found.")
        return try {
            resolveWithinProject(base.toString(), relativePath, allowBlankAsDot)
        } catch (e: IllegalArgumentException) {
            val escapedAbsolute = escapedAbsolutePath(relativePath)
            if (escapedAbsolute != null) {
                actualPath(escapedAbsolute)
            } else {
                throw e
            }
        }
    }

    private fun escapedAbsolutePath(relativePath: String?): Path? {
        val raw = relativePath?.trim().orEmpty()
        if (raw.isEmpty()) return null
        try {
            val direct = Paths.get(raw)
            if (direct.isAbsolute) return direct.toAbsolutePath().normalize()
        } catch (_: Throwable) {
        }
        val unixTail = raw.replace('\\', '/').replace(Regex("""^(\.\./)+"""), "")
        return if (unixTail.startsWith("/")) {
            Paths.get(unixTail).toAbsolutePath().normalize()
        } else if ((raw.startsWith("../") || raw.startsWith("..\\")) && unixTail.isNotBlank()) {
            Paths.get("/" + unixTail).toAbsolutePath().normalize()
        } else {
            null
        }
    }

    /**
     * Return a project-relative path (with forward slashes) for a given absolute path.
     */
    @JvmStatic
    fun relativizeToProject(
        projectBase: String,
        absolutePath: Path,
    ): String {
        val base = actualPath(Paths.get(projectBase).toAbsolutePath().normalize())
        val rel = base.relativize(actualPath(absolutePath.toAbsolutePath().normalize())).toString()
        return rel.replace('\\', '/')
    }

    @JvmStatic
    fun relativizeToProject(
        project: Project,
        absolutePath: Path,
    ): String {
        val base = projectRootNioPath(project) ?: throw IllegalArgumentException("Project root not found.")
        val rel = base.relativize(actualPath(absolutePath.toAbsolutePath().normalize())).toString()
        return rel.replace('\\', '/')
    }

    @JvmStatic
    fun actualPath(path: Path): Path =
        try {
            EelPathUtils.getActualPath(path).toAbsolutePath().normalize()
        } catch (_: Throwable) {
            path.toAbsolutePath().normalize()
        }
}
