// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.coroutines

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.platform.util.coroutines.childScope
import kotlinx.coroutines.CoroutineScope

/**
 * A service-level class that provides and manages coroutine scopes for a given project.
 *
 * @constructor Initializes the [CoroutineScopeHolder] with a project-wide coroutine scope.
 * @param projectWideCoroutineScope A [kotlinx.coroutines.CoroutineScope] defining the lifecycle of project-wide coroutines.
 */
@Service(Level.PROJECT)
internal class CoroutineScopeHolder(
    private val projectWideCoroutineScope: CoroutineScope,
) {
    companion object {
        fun getInstance(project: Project): CoroutineScopeHolder = project.getService(CoroutineScopeHolder::class.java)
    }

    /**
     * Creates a new coroutine scope as a child of the project-wide coroutine scope with the specified name.
     *
     * @param name The name for the newly created coroutine scope.
     * @return a scope with a [kotlinx.coroutines.Job] which parent is the [kotlinx.coroutines.Job] of [projectWideCoroutineScope] scope.
     */
    fun createScope(name: String): CoroutineScope = projectWideCoroutineScope.childScope(name)

    fun getPluginScope(): CoroutineScope = projectWideCoroutineScope
}
