package com.aziitech.devmonitor.gateway

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.ArrayDeque

data class SmsValues(val todayCount: Int?, val mtdCount: Int?, val active: Int?, val bar48: Int?, val bar96: Int?, val duplicates: Int?, val monthlyTarget: Int?)

data class DailySmsResult(
    val accessScope: String, val canonicalDeviceId: String, val businessDate: LocalDate,
    val checkedAt: Instant, val receivedAt: Instant, val values: SmsValues,
    val gatewayId: String, val jobId: String, val attemptId: String, val parserVersion: String
)

data class CheckJob(
    val id: String = UUID.randomUUID().toString(),
    val accessScope: String, val canonicalDeviceId: String, val queryProfile: String,
    val enqueuedAt: Instant, val idempotencyKey: String,
    /** Optional owner-paired gateway; validated against registered gateway ownership. */
    val preferredGatewayId: String? = null
)

enum class GatewayHealth { READY, OFFLINE, NOT_READY, QUARANTINED }

data class GatewayWorker(
    val id: String,
    var health: GatewayHealth = GatewayHealth.READY,
    var activeJobId: String? = null,
    val ownerAccessScope: String? = null,
    var acceptPoolJobs: Boolean = true
)

data class JobLease(val job: CheckJob, val gatewayId: String, val leaseToken: String, val attemptId: String)
enum class JobState { QUEUED, LEASED, COMPLETED, AMBIGUOUS }

interface DailyResultStore {
    fun upsertLatestForDay(result: DailySmsResult)
    fun latest(accessScope: String, canonicalDeviceId: String): DailySmsResult?
    fun history(accessScope: String, canonicalDeviceId: String, from: LocalDate, to: LocalDate): List<DailySmsResult>
}

/** Volatile adapter for deterministic prototype tests only. */
class InMemoryDailyResultStore : DailyResultStore {
    private val rows = mutableMapOf<Triple<String, String, LocalDate>, DailySmsResult>()
    @Synchronized override fun upsertLatestForDay(result: DailySmsResult) {
        val key = Triple(result.accessScope, result.canonicalDeviceId, result.businessDate)
        val old = rows[key]
        if (old == null || result.checkedAt.isAfter(old.checkedAt)) rows[key] = result
    }
    @Synchronized override fun latest(accessScope: String, canonicalDeviceId: String): DailySmsResult? =
        rows.values.filter { it.accessScope == accessScope && it.canonicalDeviceId == canonicalDeviceId }.maxByOrNull { it.checkedAt }
    @Synchronized override fun history(accessScope: String, canonicalDeviceId: String, from: LocalDate, to: LocalDate): List<DailySmsResult> =
        rows.values.filter { it.accessScope == accessScope && it.canonicalDeviceId == canonicalDeviceId &&
            !it.businessDate.isBefore(from) && !it.businessDate.isAfter(to) }.sortedBy { it.businessDate }
}

/**
 * Single-process reference scheduler. Production requires durable transactional storage and
 * server-derived ownership. Owner jobs take priority on their paired gateway; shared jobs
 * rotate fairly across access scopes. Active SMS transactions are never preempted.
 */
class GatewayJobCoordinator(private val resultStore: DailyResultStore) {
    private val workers = linkedMapOf<String, GatewayWorker>()
    private val queuesByScope = linkedMapOf<String, ArrayDeque<CheckJob>>()
    private val scopeRotation = mutableListOf<String>()
    private var nextScopeIndex = 0
    private val leases = mutableMapOf<String, JobLease>()
    private val states = mutableMapOf<String, JobState>()
    private val jobsById = mutableMapOf<String, CheckJob>()
    private val idempotency = mutableMapOf<Pair<String, String>, String>()

    @Synchronized fun register(worker: GatewayWorker) {
        val old = workers[worker.id]
        require(old == null || old.activeJobId == null) { "Cannot replace a gateway with an active job" }
        workers[worker.id] = worker
    }

    @Synchronized fun setPoolParticipation(gatewayId: String, enabled: Boolean) {
        requireNotNull(workers[gatewayId]) { "Unknown gateway" }.acceptPoolJobs = enabled
    }

    @Synchronized fun setHealth(gatewayId: String, health: GatewayHealth) {
        val worker = requireNotNull(workers[gatewayId]) { "Unknown gateway" }
        if (worker.health == GatewayHealth.QUARANTINED && health == GatewayHealth.READY) return
        worker.health = health
    }

    @Synchronized fun enqueue(job: CheckJob): CheckJob {
        val key = job.accessScope to job.idempotencyKey
        idempotency[key]?.let { return findJob(it) }
        job.preferredGatewayId?.let { id ->
            val owner = requireNotNull(workers[id]) { "Unknown preferred gateway" }
            require(owner.ownerAccessScope == job.accessScope) { "Preferred gateway is not owned by this access scope" }
        }
        idempotency[key] = job.id
        jobsById[job.id] = job
        queuesByScope.getOrPut(job.accessScope) {
            scopeRotation.add(job.accessScope)
            ArrayDeque()
        }.addLast(job)
        states[job.id] = JobState.QUEUED
        return job
    }

    /** Owner jobs first, then shared jobs using round-robin tenant scheduling. */
    @Synchronized fun claimNext(gatewayId: String): JobLease? {
        val worker = requireNotNull(workers[gatewayId]) { "Unknown gateway" }
        if (worker.health != GatewayHealth.READY || worker.activeJobId != null) return null
        val personal = pollPersonalJob(worker)
        val job = personal ?: if (worker.acceptPoolJobs) pollFairSharedJob(worker) else null
        if (job == null) return null
        val lease = JobLease(job, gatewayId, UUID.randomUUID().toString(), UUID.randomUUID().toString())
        leases[job.id] = lease
        states[job.id] = JobState.LEASED
        worker.activeJobId = job.id
        return lease
    }

    @Synchronized fun complete(lease: JobLease, result: DailySmsResult): Boolean {
        val current = leases[lease.job.id] ?: return false
        if (current.leaseToken != lease.leaseToken || current.gatewayId != lease.gatewayId) return false
        if (result.jobId != lease.job.id || result.attemptId != lease.attemptId ||
            result.gatewayId != lease.gatewayId || result.accessScope != lease.job.accessScope ||
            result.canonicalDeviceId != lease.job.canonicalDeviceId) return false
        resultStore.upsertLatestForDay(result)
        states[lease.job.id] = JobState.COMPLETED
        leases.remove(lease.job.id)
        workers[lease.gatewayId]?.activeJobId = null
        return true
    }

    /** Ambiguous because 3030 replies do not echo the queried device code. */
    @Synchronized fun markTimedOutAmbiguous(lease: JobLease): Boolean {
        val current = leases[lease.job.id] ?: return false
        if (current.leaseToken != lease.leaseToken) return false
        states[lease.job.id] = JobState.AMBIGUOUS
        leases.remove(lease.job.id)
        workers[lease.gatewayId]?.apply { activeJobId = null; health = GatewayHealth.QUARANTINED }
        return true
    }

    @Synchronized fun releaseQuarantineAfterOperatorResolution(gatewayId: String) {
        val worker = requireNotNull(workers[gatewayId]) { "Unknown gateway" }
        require(worker.health == GatewayHealth.QUARANTINED) { "Gateway is not quarantined" }
        require(worker.activeJobId == null) { "Gateway still has an active job" }
        worker.health = GatewayHealth.READY
    }

    @Synchronized fun state(jobId: String): JobState? = states[jobId]
    fun cachedLatest(accessScope: String, deviceId: String): DailySmsResult? = resultStore.latest(accessScope, deviceId)
    fun cachedHistory(accessScope: String, deviceId: String, from: LocalDate, to: LocalDate): List<DailySmsResult> =
        resultStore.history(accessScope, deviceId, from, to)

    private fun pollPersonalJob(worker: GatewayWorker): CheckJob? {
        val scope = worker.ownerAccessScope ?: return null
        val queue = queuesByScope[scope] ?: return null
        val job = queue.firstOrNull { it.preferredGatewayId == worker.id } ?: return null
        queue.remove(job)
        pruneEmptyScope(scope)
        return job
    }

    private fun pollFairSharedJob(worker: GatewayWorker): CheckJob? {
        if (scopeRotation.isEmpty()) return null
        val count = scopeRotation.size
        repeat(count) {
            if (scopeRotation.isEmpty()) return null
            if (nextScopeIndex >= scopeRotation.size) nextScopeIndex = 0
            val scope = scopeRotation[nextScopeIndex]
            val queue = queuesByScope[scope]
            if (queue == null || queue.isEmpty()) {
                removeScope(scope)
                return@repeat
            }
            val candidate = queue.firstOrNull { isAvailableToSharedWorker(it, worker) }
            if (candidate != null) {
                queue.remove(candidate)
                val index = scopeRotation.indexOf(scope)
                if (index >= 0 && scopeRotation.isNotEmpty()) nextScopeIndex = (index + 1) % scopeRotation.size
                pruneEmptyScope(scope)
                return candidate
            }
            nextScopeIndex = (nextScopeIndex + 1) % scopeRotation.size
        }
        return null
    }

    private fun isAvailableToSharedWorker(job: CheckJob, worker: GatewayWorker): Boolean {
        val preferredId = job.preferredGatewayId ?: return true
        if (preferredId == worker.id) return true
        val preferred = workers[preferredId] ?: return true
        return preferred.health != GatewayHealth.READY
    }

    private fun pruneEmptyScope(scope: String) {
        if (queuesByScope[scope]?.isNotEmpty() == true) return
        queuesByScope.remove(scope)
        removeScope(scope)
    }

    private fun removeScope(scope: String) {
        val index = scopeRotation.indexOf(scope)
        if (index < 0) return
        scopeRotation.removeAt(index)
        if (scopeRotation.isEmpty()) nextScopeIndex = 0
        else {
            if (index < nextScopeIndex) nextScopeIndex--
            if (nextScopeIndex >= scopeRotation.size) nextScopeIndex = 0
        }
    }

    private fun findJob(jobId: String): CheckJob = jobsById[jobId] ?: error("Unknown idempotent job")
}
