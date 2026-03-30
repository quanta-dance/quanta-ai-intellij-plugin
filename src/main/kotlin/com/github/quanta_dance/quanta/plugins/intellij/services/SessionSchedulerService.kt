// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit



@Service(Service.Level.PROJECT)
class SessionSchedulerService(
    private val project: Project,
) : Disposable {
    data class JobInfo(
        val id: String,
        // Logical name/category (e.g., "check-mr-status").
        val name: String,
        val message: String,
        val nextRunAtMs: Long,
        // Owner agent id. Contract: exactly one pending one-shot job per agent.
        val ownerAgentId: String,
    )



    private val log = Logger.getInstance(SessionSchedulerService::class.java)

    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "quanta-session-scheduler").apply { isDaemon = true }
    }

    private val jobs = ConcurrentHashMap<String, JobInfo>()
    private val futures = ConcurrentHashMap<String, ScheduledFuture<*>>()

    // Enforce: at most one pending one-shot job per agent.
    private val agentJobIds = ConcurrentHashMap<String, String>()



    override fun dispose() {
        try {
            futures.values.forEach { f ->
                try {
                    f.cancel(true)
                } catch (_: Throwable) {
                }
            }
            futures.clear()
            jobs.clear()
        } catch (_: Throwable) {
        }
        try {
            exec.shutdownNow()
        } catch (_: Throwable) {
        }
    }

    fun list(): List<JobInfo> = jobs.values.sortedBy { it.nextRunAtMs }

    fun cancel(jobId: String): Boolean {
        futures.remove(jobId)?.let { f ->
            try {
                f.cancel(true)
            } catch (_: Throwable) {
            }
        }

        val removed = jobs.remove(jobId)

        if (removed != null) {
            agentJobIds.remove(removed.ownerAgentId, jobId)
        }
        return removed != null

    }


    fun add(
        name: String,
        message: String,
        delayMs: Long,
        ownerAgentId: String,
    ): JobInfo {
        val id = UUID.randomUUID().toString()

        // Only one one-shot scheduler per agent.
        val prevId = agentJobIds.put(ownerAgentId, id)
        if (prevId != null && prevId != id) cancel(prevId)


        val now = System.currentTimeMillis()
        val next = now + delayMs
        val info = JobInfo(
            id = id,
            name = name,
            message = message,
            nextRunAtMs = next,
            ownerAgentId = ownerAgentId,
        )
        jobs[id] = info

        val runnable = Runnable { onTrigger(id) }
        val future = exec.schedule(runnable, delayMs, TimeUnit.MILLISECONDS)
        futures[id] = future
        return info
    }


    private fun onTrigger(jobId: String) {
        val info = jobs[jobId] ?: return
        runJob(info)
    }

    private fun runJob(info: JobInfo) {
        try {
            val svc = project.service<AgentManagerService>()
            val text = "[scheduled:${info.name}] ${info.message}".trim()
            svc.postInboxMessage(
                toAgentId = info.ownerAgentId,
                from = "scheduler",
                text = text,
                kind = "scheduled",
            )
        } catch (t: Throwable) {
            log.warn("Scheduled job run failed: ${t.message}", t)
        } finally {
            // Always one-shot: remove after run.
            cancel(info.id)
        }
    }

}
