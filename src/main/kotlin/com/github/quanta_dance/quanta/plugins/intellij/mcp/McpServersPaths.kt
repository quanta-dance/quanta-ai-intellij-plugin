package com.github.quanta_dance.quanta.plugins.intellij.mcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtilRt
import java.nio.file.Path

object McpServersPaths {
    const val DIR_NAME = ".quantadance"
    const val FILE_NAME = "mcp-servers.json"
    const val RELATIVE_UNIX = "/$DIR_NAME/$FILE_NAME"

    fun resolve(project: Project): Path? {
        val base = project.basePath ?: return null
        return Path.of(base, DIR_NAME, FILE_NAME)
    }

}
