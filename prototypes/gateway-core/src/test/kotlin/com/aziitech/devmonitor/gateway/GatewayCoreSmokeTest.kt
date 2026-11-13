package com.aziitech.devmonitor.gateway

import java.time.Instant
import java.time.LocalDate

/** Dependency-free smoke test; run with kotlinc and kotlin. */
fun main() {
    val store = InMemoryDailyResultStore()
    val coordinator = GatewayJobCoordinator(store)
    coordinator.register(GatewayWorker("X"))
    coordinator.register(GatewayWorker("Y"))
    coordinator.register(GatewayWorker("Z"))

    val jobA = coordinator.enqueue(CheckJob(accessScope="company", canonicalDeviceId="GLO-GD-A1", queryProfile="standard", enqueuedAt=Instant.parse("2026-11-13T09:00:00Z"), idempotencyKey="a"))
    val jobB = coordinator.enqueue(CheckJob(accessScope="company", canonicalDeviceId="GLO-GD-B2", queryProfile="standard", enqueuedAt=Instant.parse("2026-11-13T09:01:00Z"), idempotencyKey="b"))
    val leaseX = requireNotNull(coordinator.claimNext("X"))
    val leaseY = requireNotNull(coordinator.claimNext("Y"))
    check(leaseX.job.id == jobA.id && leaseY.job.id == jobB.id)

    val result = DailySmsResult("company", "GLO-GD-A1", LocalDate.parse("2026-11-13"), Instant.parse("2026-11-13T09:02:00Z"), Instant.parse("2026-11-13T09:02:03Z"), SmsValues(12, 120, 90, 4, 2, 0, 200), "X", jobA.id, leaseX.attemptId, "parser-1")
    check(coordinator.complete(leaseX, result))
    check(coordinator.cachedLatest("company", "GLO-GD-A1") == result)

    check(coordinator.markTimedOutAmbiguous(leaseY))
    check(coordinator.claimNext("Y") == null)
    check(coordinator.state(jobB.id) == JobState.AMBIGUOUS)
    check(coordinator.claimNext("Z") == null)

    // A late, older check cannot overwrite the day's newer accepted value.
    store.upsertLatestForDay(result.copy(checkedAt=Instant.parse("2026-11-13T08:00:00Z")))
    check(store.latest("company", "GLO-GD-A1") == result)

    // Idempotent retry returns the same job even after completion.
    check(coordinator.enqueue(jobA).id == jobA.id)
}
