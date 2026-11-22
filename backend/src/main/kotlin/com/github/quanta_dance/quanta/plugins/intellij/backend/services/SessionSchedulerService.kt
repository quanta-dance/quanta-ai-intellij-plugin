// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.ChatConversationService
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped scheduler for follow-up manager turns and reminder jobs.
 *
 * It keeps lightweight in-memory timers for the current IDE session and routes fired reminders back
 * into chat through [ChatConversationService].
 */
@Service(Service.Level.PROJECT)
class SessionSchedulerService(
    @Suppress("unused") private val project: Project,
) : Disposable {
    data class JobInfo(
        val id: String,
        val name: String,
        val message: String,
        val nextRunAtMs: Long,
        val ownerAgentId: String,
    )

    private val logger = Logger.getInstance(SessionSchedulerService::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val jobs = ConcurrentHashMap<String, JobInfo>()
    private val scheduledJobs = ConcurrentHashMap<String, Job>()
    private val agentJobIds = ConcurrentHashMap<String, String>()

    override fun dispose() {
        scope.cancel()
        jobs.clear()
        scheduledJobs.clear()
        agentJobIds.clear()
    }

    fun list(): List<JobInfo> = jobs.values.sortedBy { it.nextRunAtMs }

    fun cancel(jobId: String): Boolean {
        scheduledJobs.remove(jobId)?.cancel()
        val job = jobs.remove(jobId) ?: return false
        agentJobIds.remove(job.ownerAgentId, jobId)
        return true
    }

    fun add(
        name: String,
        message: String,
        delayMs: Long,
        ownerAgentId: String,
    ): JobInfo {
        val id = UUID.randomUUID().toString()
        val info = JobInfo(id, name, message, System.currentTimeMillis() + delayMs, ownerAgentId)
        jobs[id] = info
        agentJobIds.put(ownerAgentId, id)?.let { previousJobId -> cancel(previousJobId) }
        val scheduledJob =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(delayMs.coerceAtLeast(0))
                fireJob(info)
            }
        scheduledJobs[id] = scheduledJob
        scheduledJob.start()
        return info
    }

    private suspend fun fireJob(job: JobInfo) {
        if (!jobs.remove(job.id, job)) return
        scheduledJobs.remove(job.id)
        agentJobIds.remove(job.ownerAgentId, job.id)
        try {
            val reminderContext =
                buildString {
                    appendLine("A scheduled reminder has fired.")
                    appendLine("Reminder context: ${job.message}")
                    appendLine("Respond naturally to the user with a friendly reminder.")
                    appendLine("Do not echo the reminder context verbatim or say that the reminder was acknowledged.")
                }
            project.service<ChatConversationService>().sendScheduledReminder(reminderContext)
            QDLog.debug(logger) { "Scheduled reminder fired: job=${job.id} owner=${job.ownerAgentId}" }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            QDLog.warn(logger, { "Failed to fire scheduled reminder job=${job.id}" }, t)
        }
    }
}
