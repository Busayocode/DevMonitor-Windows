package com.aziitech.devmonitor.gateway

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.ArrayDeque

/** Normalized 3030 values. Nullable fields preserve exactly what the parser received. */
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
    val accessScope: String,
    val canonicalDeviceId: String,
    val queryProfile: String,
    val enqueuedAt: Instant,
    val idempotencyKey: String
)

enum class GatewayHealth { READY, OFFLINE, NOT_READY, QUARANTINED }

data class GatewayWorker(
    val id: String,
    var health: GatewayHealth = GatewayHealth.READY,
    var activeJobId: String? = null
)

data class JobLease(
    val job: CheckJob,
    val gatewayId: String,
    val leaseToken: String,
    val attemptId: String
)

enum class JobState { QUEUED, LEASED, COMPLETED, AMBIGUOUS }

/** Persistence boundary. Production implementation must be durable and transaction-safe. */
interface DailyResultStore {
    fun upsertLatestForDay(result: DailySmsResult)
    fun latest(accessScope: String, canonicalDeviceId: String): DailySmsResult?
    fun history(accessScope: String, canonicalDeviceId: String, from: LocalDate, to: LocalDate): List<DailySmsResult>
}

/** In-memory adapter for deterministic prototype tests only; not production persistence. */
class InMemoryDailyResultStore : DailyResultStore {
    private val rows = mutableMapOf<Triple<String, String, LocalDate>, DailySmsResult>()

    @Synchronized
    override fun upsertLatestForDay(result: DailySmsResult) {
        val key = Triple(result.accessScope, result.canonicalDeviceId, result.businessDate)
        val old = rows[key]
        // A delayed/older check must not replace a newer accepted daily value.
        if (old == null || result.checkedAt.isAfter(old.checkedAt)) rows[key] = result
    }

    @Synchronized
    override fun latest(accessScope: String, canonicalDeviceId: String): DailySmsResult? =
        rows.values.filter { it.accessScope == accessScope && it.canonicalDeviceId == canonicalDeviceId }
            .maxByOrNull { it.checkedAt }

    @Synchronized
    override fun history(accessScope: String, canonicalDeviceId: String, from: LocalDate, to: LocalDate): List<DailySmsResult> =
        rows.values.filter {
            it.accessScope == accessScope && it.canonicalDeviceId == canonicalDeviceId &&
                !it.businessDate.isBefore(from) && !it.businessDate.isAfter(to)
        }.sortedBy { it.businessDate }
}

/**
 * Single-process reference scheduler. A real controller must implement the same invariants
 * with a durable transactional queue, server-side authorization and distributed leases.
 */
class GatewayJobCoordinator(private val resultStore: DailyResultStore) {
    private val workers = linkedMapOf<String, GatewayWorker>()
    private val queue = ArrayDeque<CheckJob>()
    private val leases = mutableMapOf<String, JobLease>()
    private val states = mutableMapOf<String, JobState>()
    private val jobsById = mutableMapOf<String, CheckJob>()
    private val idempotency = mutableMapOf<Pair<String, String>, String>()

    @Synchronized
    fun register(worker: GatewayWorker) { workers[worker.id] = worker }

    @Synchronized
    fun setHealth(gatewayId: String, health: GatewayHealth) {
        val worker = requireNotNull(workers[gatewayId]) { "Unknown gateway" }
        // A quarantined channel cannot be made READY by an ordinary heartbeat.
        if (worker.health == GatewayHealth.QUARANTINED && health == GatewayHealth.READY) return
        worker.health = health
    }

    @Synchronized
    fun enqueue(job: CheckJob): CheckJob {
        val key = job.accessScope to job.idempotencyKey
        val existingId = idempotency[key]
        if (existingId != null) return findJob(existingId)
        idempotency[key] = job.id
        jobsById[job.id] = job
        queue.addLast(job)
        states[job.id] = JobState.QUEUED
        return job
    }

    /** Assign the oldest queued job to the first ready idle gateway. */
    @Synchronized
    fun claimNext(gatewayId: String): JobLease? {
        val worker = requireNotNull(workers[gatewayId]) { "Unknown gateway" }
        if (worker.health != GatewayHealth.READY || worker.activeJobId != null) return null
        val job = queue.pollFirst() ?: return null
        val lease = JobLease(job, gatewayId, UUID.randomUUID().toString(), UUID.randomUUID().toString())
        leases[job.id] = lease
        states[job.id] = JobState.LEASED
        worker.activeJobId = job.id
        return lease
    }

    /** Only the gateway holding the live lease may complete the assigned target. */
    @Synchronized
    fun complete(lease: JobLease, result: DailySmsResult): Boolean {
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

    /** A timeout is ambiguous because 3030 does not echo the queried device code. */
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
        // Intentionally do not put this job back into the queue or reassign it automatically.
        return true
    }

    /** Explicit operator recovery only; a quiet period alone is not proof of correlation. */
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

    fun cachedHistory(accessScope: String, deviceId: String, from: LocalDate, to: LocalDate): List<DailySmsResult> =
        resultStore.history(accessScope, deviceId, from, to)

    private fun findJob(jobId: String): CheckJob =
        jobsById[jobId] ?: error("Unknown idempotent job")
}
