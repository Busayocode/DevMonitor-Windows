package com.aziitech.devmonitor.gateway

import java.time.Instant
import java.time.LocalDate
import java.util.ArrayDeque
import java.util.UUID

data class SmsValues(
    val todayCount: Int?,
    val mtdCount: Int?,
    val active: Int?,
    val bar48: Int?,
    val bar96: Int?,
    val duplicates: Int?,
    val monthlyTarget: Int?
)

data class DailySmsResult(
    val accessScope: String,
    val canonicalDeviceId: String,
    val businessDate: LocalDate,
    val checkedAt: Instant,
    val receivedAt: Instant,
    val values: SmsValues,
    val gatewayId: String,
    val jobId: String,
    val attemptId: String,
    val parserVersion: String
)

data class CheckJob(
    val id: String = UUID.randomUUID().toString(),
    /** Tenant/company scope used to authorize and share cached history. */
    val accessScope: String,
    /** Authenticated user who requested this job; used for within-tenant fairness. */
    val requesterId: String = accessScope,
    val canonicalDeviceId: String,
    val queryProfile: String,
    val enqueuedAt: Instant,
    val idempotencyKey: String,
    val preferredGatewayId: String? = null
)

enum class GatewayHealth { READY, OFFLINE, NOT_READY, QUARANTINED }

data class GatewayWorker(
    val id: String,
    var health: GatewayHealth = GatewayHealth.READY,
    var activeJobId: String? = null,
    /** Authenticated user account that owns this personal gateway. */
    val ownerUserId: String? = null,
    var acceptPoolJobs: Boolean = true
)

data class JobLease(
    val job: CheckJob,
    val gatewayId: String,
    val leaseToken: String,
    val attemptId: String
)

enum class JobState { QUEUED, LEASED, COMPLETED, AMBIGUOUS }

interface DailyResultStore {
    fun upsertLatestForDay(result: DailySmsResult)
    fun latest(accessScope: String, canonicalDeviceId: String): DailySmsResult?
    fun history(accessScope: String, canonicalDeviceId: String, from: LocalDate, to: LocalDate): List<DailySmsResult>
}

/** Volatile adapter for deterministic prototype tests only. */
class InMemoryDailyResultStore : DailyResultStore {
    private val rows = mutableMapOf<Triple<String, String, LocalDate>, DailySmsResult>()

    @Synchronized
    override fun upsertLatestForDay(result: DailySmsResult) {
        val key = Triple(result.accessScope, result.canonicalDeviceId, result.businessDate)
        val old = rows[key]
        if (old == null || result.checkedAt.isAfter(old.checkedAt)) rows[key] = result
    }

    @Synchronized
    override fun latest(accessScope: String, canonicalDeviceId: String): DailySmsResult? =
        rows.values
            .filter { it.accessScope == accessScope && it.canonicalDeviceId == canonicalDeviceId }
            .maxByOrNull { it.checkedAt }

    @Synchronized
    override fun history(
        accessScope: String,
        canonicalDeviceId: String,
        from: LocalDate,
        to: LocalDate
    ): List<DailySmsResult> =
        rows.values.filter {
            it.accessScope == accessScope &&
                it.canonicalDeviceId == canonicalDeviceId &&
                !it.businessDate.isBefore(from) &&
                !it.businessDate.isAfter(to)
        }.sortedBy { it.businessDate }
}

/**
 * Single-process reference scheduler. Production uses the PostgreSQL controller.
 *
 * Scheduling is hierarchical: rotate tenants first, then requesters within a tenant.
 * Personal jobs are claimed by their authenticated owner before shared-pool work.
 * Active SMS transactions are never preempted.
 */
class GatewayJobCoordinator(private val resultStore: DailyResultStore) {
    private data class QueueKey(val tenantId: String, val requesterId: String)

    private val workers = linkedMapOf<String, GatewayWorker>()
    private val queues = linkedMapOf<QueueKey, ArrayDeque<CheckJob>>()
    private val tenantRotation = mutableListOf<String>()
    private val requestersByTenant = mutableMapOf<String, MutableList<String>>()
    private val nextRequesterIndex = mutableMapOf<String, Int>()
    private var nextTenantIndex = 0

    private val leases = mutableMapOf<String, JobLease>()
    private val states = mutableMapOf<String, JobState>()
    private val jobsById = mutableMapOf<String, CheckJob>()
    private val idempotency = mutableMapOf<Triple<String, String, String>, String>()

    @Synchronized
    fun register(worker: GatewayWorker) {
        val old = workers[worker.id]
        require(old == null || old.activeJobId == null) { "Cannot replace a gateway with an active job" }
        workers[worker.id] = worker
    }

    @Synchronized
    fun setPoolParticipation(gatewayId: String, enabled: Boolean) {
        requireNotNull(workers[gatewayId]) { "Unknown gateway" }.acceptPoolJobs = enabled
    }

    @Synchronized
    fun setHealth(gatewayId: String, health: GatewayHealth) {
        val worker = requireNotNull(workers[gatewayId]) { "Unknown gateway" }
        if (worker.health == GatewayHealth.QUARANTINED && health == GatewayHealth.READY) return
        worker.health = health
    }

    @Synchronized
    fun enqueue(job: CheckJob): CheckJob {
        val key = Triple(job.accessScope, job.requesterId, job.idempotencyKey)
        idempotency[key]?.let { return findJob(it) }

        job.preferredGatewayId?.let { gatewayId ->
            val owner = requireNotNull(workers[gatewayId]) { "Unknown preferred gateway" }
            require(owner.ownerUserId == job.requesterId) {
                "Preferred gateway is not owned by the requesting user"
            }
        }

        idempotency[key] = job.id
        jobsById[job.id] = job
        val queueKey = QueueKey(job.accessScope, job.requesterId)
        queues.getOrPut(queueKey) { ArrayDeque() }.addLast(job)

        if (job.accessScope !in tenantRotation) {
            tenantRotation.add(job.accessScope)
            requestersByTenant[job.accessScope] = mutableListOf()
            nextRequesterIndex[job.accessScope] = 0
        }
        val requesters = requestersByTenant.getValue(job.accessScope)
        if (job.requesterId !in requesters) requesters.add(job.requesterId)

        states[job.id] = JobState.QUEUED
        return job
    }

    /** Owner jobs first; shared jobs use tenant round-robin then requester round-robin. */
    @Synchronized
    fun claimNext(gatewayId: String): JobLease? {
        val worker = requireNotNull(workers[gatewayId]) { "Unknown gateway" }
        if (worker.health != GatewayHealth.READY || worker.activeJobId != null) return null

        val personalJob = pollPersonalJob(worker)
        val job = personalJob ?: if (worker.acceptPoolJobs) pollFairSharedJob(worker) else null
        if (job == null) return null

        val lease = JobLease(
            job = job,
            gatewayId = gatewayId,
            leaseToken = UUID.randomUUID().toString(),
            attemptId = UUID.randomUUID().toString()
        )
        leases[job.id] = lease
        states[job.id] = JobState.LEASED
        worker.activeJobId = job.id
        return lease
    }

    @Synchronized
    fun complete(lease: JobLease, result: DailySmsResult): Boolean {
        val current = leases[lease.job.id] ?: return false
        if (current.leaseToken != lease.leaseToken || current.gatewayId != lease.gatewayId) return false
        if (result.jobId != lease.job.id ||
            result.attemptId != lease.attemptId ||
            result.gatewayId != lease.gatewayId ||
            result.accessScope != lease.job.accessScope ||
            result.canonicalDeviceId != lease.job.canonicalDeviceId
        ) return false

        resultStore.upsertLatestForDay(result)
        states[lease.job.id] = JobState.COMPLETED
        leases.remove(lease.job.id)
        workers[lease.gatewayId]?.activeJobId = null
        return true
    }

    /** Ambiguous because 3030 replies do not echo the queried device code. */
    @Synchronized
    fun markTimedOutAmbiguous(lease: JobLease): Boolean {
        val current = leases[lease.job.id] ?: return false
        if (current.leaseToken != lease.leaseToken) return false
        states[lease.job.id] = JobState.AMBIGUOUS
        leases.remove(lease.job.id)
        workers[lease.gatewayId]?.apply {
            activeJobId = null
            health = GatewayHealth.QUARANTINED
        }
        return true
    }

    @Synchronized
    fun releaseQuarantineAfterOperatorResolution(gatewayId: String) {
        val worker = requireNotNull(workers[gatewayId]) { "Unknown gateway" }
        require(worker.health == GatewayHealth.QUARANTINED) { "Gateway is not quarantined" }
        require(worker.activeJobId == null) { "Gateway still has an active job" }
        worker.health = GatewayHealth.READY
    }

    @Synchronized
    fun state(jobId: String): JobState? = states[jobId]

    fun cachedLatest(accessScope: String, deviceId: String): DailySmsResult? =
        resultStore.latest(accessScope, deviceId)

    fun cachedHistory(
        accessScope: String,
        deviceId: String,
        from: LocalDate,
        to: LocalDate
    ): List<DailySmsResult> = resultStore.history(accessScope, deviceId, from, to)

    private fun pollPersonalJob(worker: GatewayWorker): CheckJob? {
        val owner = worker.ownerUserId ?: return null
        val candidates = queues.values
            .asSequence()
            .flatMap { it.asSequence() }
            .filter { it.requesterId == owner && it.preferredGatewayId == worker.id }
            .sortedBy { it.enqueuedAt }
            .toList()
        val job = candidates.firstOrNull() ?: return null
        removeFromQueue(job)
        return job
    }

    private fun pollFairSharedJob(worker: GatewayWorker): CheckJob? {
        if (tenantRotation.isEmpty()) return null
        val tenantCount = tenantRotation.size

        repeat(tenantCount) {
            if (tenantRotation.isEmpty()) return null
            if (nextTenantIndex >= tenantRotation.size) nextTenantIndex = 0
            val tenant = tenantRotation[nextTenantIndex]
            val requesters = requestersByTenant[tenant].orEmpty()
            if (requesters.isEmpty()) {
                nextTenantIndex = (nextTenantIndex + 1) % tenantRotation.size
                return@repeat
            }

            val start = (nextRequesterIndex[tenant] ?: 0).mod(requesters.size)
            repeat(requesters.size) { offset ->
                val requesterIndex = (start + offset) % requesters.size
                val requester = requesters[requesterIndex]
                val queue = queues[QueueKey(tenant, requester)] ?: return@repeat
                val candidate = queue.firstOrNull { isAvailableToSharedWorker(it, worker) }
                    ?: return@repeat

                queue.remove(candidate)
                nextRequesterIndex[tenant] = (requesterIndex + 1) % requesters.size
                nextTenantIndex = (tenantRotation.indexOf(tenant) + 1) % tenantRotation.size
                return candidate
            }
            nextTenantIndex = (nextTenantIndex + 1) % tenantRotation.size
        }
        return null
    }

    private fun isAvailableToSharedWorker(job: CheckJob, worker: GatewayWorker): Boolean {
        val preferredId = job.preferredGatewayId ?: return true
        if (preferredId == worker.id) return true
        val preferred = workers[preferredId] ?: return true
        return preferred.health != GatewayHealth.READY
    }

    private fun removeFromQueue(job: CheckJob) {
        queues[QueueKey(job.accessScope, job.requesterId)]?.remove(job)
    }

    private fun findJob(jobId: String): CheckJob = jobsById[jobId] ?: error("Unknown idempotent job")
}
