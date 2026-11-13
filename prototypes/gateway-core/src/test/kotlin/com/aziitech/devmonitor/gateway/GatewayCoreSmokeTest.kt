package com.aziitech.devmonitor.gateway

import java.time.Instant
import java.time.LocalDate

fun main() {
    val store = InMemoryDailyResultStore()
    val coordinator = GatewayJobCoordinator(store)
    coordinator.register(GatewayWorker("X"))
    coordinator.register(GatewayWorker("Y"))
    coordinator.register(GatewayWorker("Z"))
    val jobA = coordinator.enqueue(job("company", "a", "GLO-GD-A1"))
    val jobB = coordinator.enqueue(job("company", "b", "GLO-GD-B2"))
    val leaseX = requireNotNull(coordinator.claimNext("X"))
    val leaseY = requireNotNull(coordinator.claimNext("Y"))
    check(leaseX.job.id == jobA.id && leaseY.job.id == jobB.id)
    val result = resultFor(leaseX)
    check(coordinator.complete(leaseX, result))
    check(coordinator.cachedLatest("company", "GLO-GD-A1") == result)
    check(coordinator.markTimedOutAmbiguous(leaseY))
    check(coordinator.claimNext("Y") == null)
    check(coordinator.state(jobB.id) == JobState.AMBIGUOUS)
    check(coordinator.claimNext("Z") == null)
    store.upsertLatestForDay(result.copy(checkedAt=Instant.parse("2026-11-13T08:00:00Z")))
    check(store.latest("company", "GLO-GD-A1") == result)
    check(coordinator.enqueue(jobA).id == jobA.id)

    // Tenant fairness: B gets a turn before A's full queue drains.
    val fair = GatewayJobCoordinator(InMemoryDailyResultStore())
    fair.register(GatewayWorker("F1")); fair.register(GatewayWorker("F2"))
    val a1 = fair.enqueue(job("user-A", "A1"))
    fair.enqueue(job("user-A", "A2")); fair.enqueue(job("user-A", "A3"))
    val b1 = fair.enqueue(job("user-B", "B1"))
    fair.enqueue(job("user-B", "B2"))
    check(fair.claimNext("F1")?.job?.id == a1.id)
    check(fair.claimNext("F2")?.job?.id == b1.id)

    // Owner's personal gateway gets personal work before older pool work.
    val personal = GatewayJobCoordinator(InMemoryDailyResultStore())
    personal.register(GatewayWorker("P", ownerAccessScope="owner", acceptPoolJobs=true))
    personal.register(GatewayWorker("POOL"))
    val sharedFirst = personal.enqueue(job("other-user", "shared-1"))
    val ownerJob = personal.enqueue(job("owner", "owner-1", preferred="P"))
    check(personal.claimNext("P")?.job?.id == ownerJob.id)
    check(personal.claimNext("POOL")?.job?.id == sharedFirst.id)

    // Do not interrupt an active pool SMS; owner work is next after it completes.
    val poolJob = personal.enqueue(job("other-user", "shared-2"))
    val poolLease = requireNotNull(personal.claimNext("P"))
    check(poolLease.job.id == poolJob.id)
    val waitingOwner = personal.enqueue(job("owner", "owner-2", preferred="P"))
    check(personal.claimNext("P") == null)
    check(personal.complete(poolLease, resultFor(poolLease)))
    check(personal.claimNext("P")?.job?.id == waitingOwner.id)

    // Offline personal gateway permits failover to another pool worker.
    val fallback = GatewayJobCoordinator(InMemoryDailyResultStore())
    fallback.register(GatewayWorker("OWNER-PHONE", health=GatewayHealth.OFFLINE, ownerAccessScope="owner"))
    fallback.register(GatewayWorker("POOL-2"))
    val fallbackJob = fallback.enqueue(job("owner", "owner-offline", preferred="OWNER-PHONE"))
    check(fallback.claimNext("POOL-2")?.job?.id == fallbackJob.id)

    // A different account cannot nominate this owner's gateway.
    val protected = GatewayJobCoordinator(InMemoryDailyResultStore())
    protected.register(GatewayWorker("PRIVATE", ownerAccessScope="owner"))
    check(runCatching { protected.enqueue(job("intruder", "spoof", preferred="PRIVATE")) }.isFailure)
}

private fun job(scope: String, key: String, device: String = "DEVICE-$key", preferred: String? = null) = CheckJob(
    accessScope=scope, canonicalDeviceId=device, queryProfile="standard",
    enqueuedAt=Instant.parse("2026-11-13T10:00:00Z"), idempotencyKey=key, preferredGatewayId=preferred
)

private fun resultFor(lease: JobLease) = DailySmsResult(
    accessScope=lease.job.accessScope, canonicalDeviceId=lease.job.canonicalDeviceId,
    businessDate=LocalDate.parse("2026-11-13"), checkedAt=Instant.parse("2026-11-13T10:05:00Z"),
    receivedAt=Instant.parse("2026-11-13T10:05:03Z"), values=SmsValues(1,10,8,0,0,0,100),
    gatewayId=lease.gatewayId, jobId=lease.job.id, attemptId=lease.attemptId, parserVersion="test"
)
