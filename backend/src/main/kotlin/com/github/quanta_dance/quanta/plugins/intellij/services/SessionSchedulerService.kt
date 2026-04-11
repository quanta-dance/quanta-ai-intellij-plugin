// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class SessionSchedulerService(@Suppress("unused") private val project: Project) : Disposable {
    data class JobInfo(
        val id: String,
        val name: String,
        val message: String,
        val nextRunAtMs: Long,
        val ownerAgentId: String,
    )

    private val jobs = ConcurrentHashMap<String, JobInfo>()
    private val agentJobIds = ConcurrentHashMap<String, String>()

    override fun dispose() {
        jobs.clear()
        agentJobIds.clear()
    }

    fun list(): List<JobInfo> = jobs.values.sortedBy { it.nextRunAtMs }

    fun cancel(jobId: String): Boolean = jobs.remove(jobId) != null

    fun add(
        name: String,
        message: String,
        delayMs: Long,
        ownerAgentId: String,
    ): JobInfo {
        val id = UUID.randomUUID().toString()
        val info = JobInfo(id, name, message, System.currentTimeMillis() + delayMs, ownerAgentId)
        jobs[id] = info
        agentJobIds[ownerAgentId] = id
        return info
    }
}
