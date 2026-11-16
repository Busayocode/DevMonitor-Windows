package com.aziitech.devmonitor.gateway.worker

import java.time.Duration
import java.time.Instant

enum class SmsChannelState { IDLE, ACTIVE, DRAINING, CANCELLATION_COOLDOWN, QUARANTINED }

data class SmsTransaction(
    val transactionId: String,
    val jobId: String,
    val attemptId: String,
    val canonicalDeviceId: String,
    val queryProfile: String,
    val startedAt: Instant,
    val responseDeadline: Instant
)

data class SmsChannelSnapshot(
    val state: SmsChannelState = SmsChannelState.IDLE,
    val transaction: SmsTransaction? = null,
    val drainUntil: Instant? = null,
    val cancellationCooldownUntil: Instant? = null,
    val seenMessageFingerprints: Set<String> = emptySet(),
    val lastResolutionNote: String? = null
)

interface SmsChannelStateStore {
    fun load(): SmsChannelSnapshot
    fun save(snapshot: SmsChannelSnapshot)
}

/** Test adapter only. Android production must supply a durable, atomic store. */
class InMemorySmsChannelStateStore(initial: SmsChannelSnapshot = SmsChannelSnapshot()) :
    SmsChannelStateStore {
    private var value = initial
    @Synchronized override fun load(): SmsChannelSnapshot = value
    @Synchronized override fun save(snapshot: SmsChannelSnapshot) { value = snapshot }
}

sealed interface InboundSmsDecision {
    data class Accepted(val transaction: SmsTransaction, val normalizedPayload: String) : InboundSmsDecision
    data class Unassigned(val reason: String) : InboundSmsDecision
    data object Duplicate : InboundSmsDecision
}

class GatewaySmsTransactionGate(
    private val store: SmsChannelStateStore,
    private val postReplyQuietPeriod: Duration
) {
    companion object {
        val CANCELLATION_COOLDOWN: Duration = Duration.ofMinutes(4)
    }

    init {
        require(!postReplyQuietPeriod.isNegative) {
            "Post-reply quiet period cannot be negative"
        }
    }

    @Synchronized
    fun begin(
        transactionId: String,
        jobId: String,
        attemptId: String,
        canonicalDeviceId: String,
        queryProfile: String,
        startedAt: Instant,
        responseTimeout: Duration
    ): SmsTransaction {
        require(transactionId.isNotBlank() && jobId.isNotBlank() && attemptId.isNotBlank())
        require(canonicalDeviceId.isNotBlank() && queryProfile.isNotBlank())
        require(!responseTimeout.isNegative && !responseTimeout.isZero)
        val current = store.load()
        val ready = when (current.state) {
            SmsChannelState.IDLE -> true
            SmsChannelState.DRAINING -> current.drainUntil?.let { !startedAt.isBefore(it) } == true
            SmsChannelState.CANCELLATION_COOLDOWN ->
                current.cancellationCooldownUntil?.let { !startedAt.isBefore(it) } == true
            else -> false
        }
        check(ready) { "SMS channel is ${current.state}; a new 3030 request is not safe" }

        val transaction = SmsTransaction(
            transactionId, jobId, attemptId, canonicalDeviceId, queryProfile,
            startedAt, startedAt.plus(responseTimeout)
        )
        store.save(current.copy(
            state = SmsChannelState.ACTIVE,
            transaction = transaction,
            drainUntil = null,
            cancellationCooldownUntil = null,
            lastResolutionNote = null
        ))
        return transaction
    }


    /**
     * Starts the independent four-minute cancellation cooldown only when the
     * exact active attempt has a confirmed successful Android SMS_SENT result.
     * Callers must not infer submission from a button press or an uncertain callback.
     */
    @Synchronized
    fun recordCancellationAfterSubmission(
        attemptId: String,
        now: Instant,
        androidSubmissionSucceeded: Boolean
    ): Boolean {
        require(attemptId.isNotBlank())
        if (!androidSubmissionSucceeded) return false
        val current = store.load()
        val tx = current.transaction ?: return false
        if (current.state != SmsChannelState.ACTIVE || tx.attemptId != attemptId) return false
        store.save(current.copy(
            state = SmsChannelState.CANCELLATION_COOLDOWN,
            transaction = null,
            drainUntil = null,
            cancellationCooldownUntil = now.plus(CANCELLATION_COOLDOWN),
            lastResolutionNote = "Cancelled after confirmed SMS submission; four-minute cooldown started."
        ))
        return true
    }

    /** Apply the controller's authoritative cancellation result to the durable local gate. */
    @Synchronized
    fun applyControllerCancellation(
        attemptId: String,
        outcome: String,
        cooldownUntil: Instant?
    ) {
        require(attemptId.isNotBlank())
        when (outcome) {
            "CANCELLED_BEFORE_SEND" -> Unit
            "CANCELLED_AFTER_SEND" -> {
                val deadline = requireNotNull(cooldownUntil) {
                    "Controller omitted the cancellation cooldown deadline"
                }
                val current = store.load()
                val active = current.transaction
                check(active == null || active.attemptId == attemptId) {
                    "A different local SMS attempt is active; cancellation result cannot be applied"
                }
                store.save(current.copy(
                    state = SmsChannelState.CANCELLATION_COOLDOWN,
                    transaction = null,
                    drainUntil = null,
                    cancellationCooldownUntil = deadline,
                    lastResolutionNote = "Controller confirmed cancellation after SMS submission; four-minute cooldown."
                ))
            }
            "BLOCKED_QUARANTINED" -> quarantine(
                "Controller blocked cancellation because SMS transmission state is uncertain"
            )
            else -> throw IllegalStateException("Unknown controller cancellation outcome")
        }
    }

    /** Call only for a newly observed SMS from the verified 3030 sender. */
    @Synchronized
    fun on3030Message(
        messageFingerprint: String,
        receivedAt: Instant,
        normalizedPayload: String
    ): InboundSmsDecision {
        require(messageFingerprint.isNotBlank())
        val current = store.load()
        if (messageFingerprint in current.seenMessageFingerprints) return InboundSmsDecision.Duplicate

        val seen = current.seenMessageFingerprints + messageFingerprint
        return when (current.state) {
            SmsChannelState.ACTIVE -> {
                val tx = current.transaction
                    ?: error("ACTIVE channel has no persisted transaction")
                if (receivedAt.isBefore(tx.startedAt)) {
                    store.save(current.copy(seenMessageFingerprints = seen))
                    InboundSmsDecision.Unassigned("Message timestamp predates the active request")
                } else if (receivedAt.isAfter(tx.responseDeadline)) {
                    store.save(current.copy(
                        state = SmsChannelState.QUARANTINED,
                        seenMessageFingerprints = seen
                    ))
                    InboundSmsDecision.Unassigned("Reply arrived after the response deadline; channel quarantined")
                } else if (normalizedPayload.isBlank()) {
                    store.save(current.copy(seenMessageFingerprints = seen))
                    InboundSmsDecision.Unassigned("Message did not produce a valid normalized 3030 result")
                } else {
                    store.save(current.copy(
                        state = SmsChannelState.DRAINING,
                        drainUntil = receivedAt.plus(postReplyQuietPeriod),
                        seenMessageFingerprints = seen
                    ))
                    InboundSmsDecision.Accepted(tx, normalizedPayload)
                }
            }
            SmsChannelState.DRAINING -> {
                store.save(current.copy(
                    drainUntil = receivedAt.plus(postReplyQuietPeriod),
                    seenMessageFingerprints = seen
                ))
                InboundSmsDecision.Unassigned("Message arrived during the post-reply drain window")
            }
            SmsChannelState.CANCELLATION_COOLDOWN -> {
                store.save(current.copy(seenMessageFingerprints = seen))
                InboundSmsDecision.Unassigned("Reply arrived during cancellation cooldown; previous attempt is not assigned")
            }
            SmsChannelState.QUARANTINED ->
                InboundSmsDecision.Unassigned("Channel is quarantined; operator resolution is required")
            SmsChannelState.IDLE -> {
                store.save(current.copy(seenMessageFingerprints = seen))
                InboundSmsDecision.Unassigned("No active 3030 transaction")
            }
        }
    }

    @Synchronized
    fun markTimedOut(now: Instant): Boolean {
        val current = store.load()
        if (current.state != SmsChannelState.ACTIVE) return false
        val tx = current.transaction ?: return false
        if (now.isBefore(tx.responseDeadline)) return false
        store.save(current.copy(state = SmsChannelState.QUARANTINED))
        return true
    }

    /** Permanently blocks the channel after a pre-send or send-result uncertainty. */
    @Synchronized
    fun quarantine(reason: String) {
        require(reason.isNotBlank())
        val current = store.load()
        store.save(current.copy(
            state = SmsChannelState.QUARANTINED,
            lastResolutionNote = "Automatic safety quarantine: ${reason.take(180)}"
        ))
    }

    @Synchronized
    fun resolveQuarantineByOperator(
        operatorId: String,
        resolutionNote: String,
        confirmedInboxDrained: Boolean
    ) {
        val current = store.load()
        check(current.state == SmsChannelState.QUARANTINED) { "Channel is not quarantined" }
        require(operatorId.isNotBlank() && resolutionNote.isNotBlank())
        check(confirmedInboxDrained) {
            "Operator must explicitly confirm the 3030 inbox/transaction was reviewed and drained"
        }
        store.save(current.copy(
            state = SmsChannelState.IDLE,
            transaction = null,
            drainUntil = null,
            cancellationCooldownUntil = null,
            lastResolutionNote = "$operatorId: $resolutionNote"
        ))
    }

    @Synchronized
    fun state(now: Instant): SmsChannelState {
        val current = store.load()
        return when (current.state) {
            SmsChannelState.DRAINING ->
                if (current.drainUntil?.let { !now.isBefore(it) } == true) SmsChannelState.IDLE else current.state
            SmsChannelState.CANCELLATION_COOLDOWN ->
                if (current.cancellationCooldownUntil?.let { !now.isBefore(it) } == true) SmsChannelState.IDLE else current.state
            else -> current.state
        }
    }
}
