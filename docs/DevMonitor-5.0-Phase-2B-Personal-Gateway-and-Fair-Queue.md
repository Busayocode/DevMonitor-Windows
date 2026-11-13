# Gateway Core Prototype — Phase 2B

Branch: devmonitor-5.0-foundation  
Status: isolated Kotlin reference scheduler; smoke-tested, not integrated into either release edition.

## Personal gateway ownership and priority
- A user may pair another DevMonitor phone as a personal gateway.
- Each personal gateway records its owner access scope and whether it opts into shared-pool work.
- A fresh check may nominate the owner's paired gateway. The coordinator verifies the gateway belongs to the job's access scope; another account cannot reserve it.
- The personal gateway claims its owner's queued jobs before shared work, even when older shared jobs are waiting.
- Other workers leave a personal job reserved while its preferred gateway is READY. If that gateway is OFFLINE, NOT_READY, QUARANTINED or unknown, another opted-in worker may claim it as failover.
- The owner can opt out of pool work while retaining personal gateway service.
- An active 3030 transaction is never interrupted. If the owner submits during another user's active transaction, it finishes safely and the owner's request wins the next claim.

## Shared-pool fairness
- Shared jobs are selected round-robin by access scope. One account's large batch therefore cannot monopolize all gateway claims.
- Personal jobs reserved for a healthy paired gateway do not block other tenants' shared jobs.
- Workers claim one job at a time dynamically; there are no fixed batch slices.

## Smoke-test coverage
1. Independent workers claim separate jobs.
2. Completed results enter the daily cache.
3. Ambiguous timeout quarantines a gateway.
4. Older delayed results cannot overwrite newer daily values.
5. Idempotent submissions do not create duplicates.
6. A second tenant gets a turn before the first tenant's batch drains.
7. Personal gateway claims its owner's work ahead of older shared work.
8. Owner work waits for an already-started pool transaction, then takes the next claim.
9. Offline personal gateway permits pool failover.
10. One account cannot nominate another account's personal gateway.

## Boundaries
- This remains a single-process in-memory reference implementation, not a production controller or durable distributed queue.
- Production must derive account scope and gateway ownership from authenticated server identity, never client-asserted fields.
- Heartbeats, durable leases, crash recovery, persistent fair-queue state, cross-process locking, authentication, authorization, tenant isolation, rate limits and server APIs remain unimplemented.
- No SMS is sent, no Android permission or manifest is changed, no backend is provisioned, and neither release edition is modified.
- Preserve the 3030 correlation rule: one unresolved transaction per physical phone/SIM; ambiguous replies must never be attached to another device check.

## Next stage
Implement durable controller storage and authenticated gateway registration/heartbeat APIs, then connect a separate gateway-phone proof of concept.
