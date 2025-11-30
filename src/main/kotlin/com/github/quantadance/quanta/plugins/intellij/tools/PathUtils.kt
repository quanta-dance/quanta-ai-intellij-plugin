// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quantadance.quanta.plugins.intellij.tools

import java.nio.file.Path
import java.nio.file.Paths

object PathUtils {
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
        val base: Path = Paths.get(projectBase).toAbsolutePath().normalize()
        val rel: String? = relativePath?.trim()
        val effective =
            when {
                rel.isNullOrEmpty() && allowBlankAsDot -> "."
                rel.isNullOrEmpty() -> throw IllegalArgumentException("Path is not specified.")
                else -> rel
            }
        val resolved: Path = base.resolve(effective).normalize()
        if (!resolved.startsWith(base)) {
            throw IllegalArgumentException("Path escapes project root: '$relativePath'")
        }
        return resolved
    }

    /**
     * Return a project-relative path (with forward slashes) for a given absolute path.
     */
    @JvmStatic
    fun relativizeToProject(
        projectBase: String,
        absolutePath: Path,
    ): String {
        val base = Paths.get(projectBase).toAbsolutePath().normalize()
        val rel = base.relativize(absolutePath.toAbsolutePath().normalize()).toString()
        return rel.replace('\\', '/')
    }
}
