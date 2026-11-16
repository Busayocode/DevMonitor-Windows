package com.aziitech.devmonitor.gateway.worker

/**
 * Pure guard for an operator cancellation request.
 *
 * This policy does not mutate local state or call the controller. In particular,
 * a sent SMS cannot be treated as cancelled until the controller acknowledges
 * closure of that exact attempt.
 */
enum class GatewayCancellationDecision {
    CANCEL_BEFORE_SEND,
    REQUIRE_CONTROLLER_CLOSURE_AND_COOLDOWN,
    BLOCK_AND_QUARANTINE
}

data class GatewayCancellationSnapshot(
    val controllerState: String,
    val localJobState: String,
    val localAttemptState: String,
    val transactionId: String?,
    val sendStartedAt: String?,
    val sentAt: String?
)

object GatewayCancellationPolicy {
    fun assess(snapshot: GatewayCancellationSnapshot): GatewayCancellationDecision {
        val controller = snapshot.controllerState.uppercase()
        val job = snapshot.localJobState.uppercase()
        val attempt = snapshot.localAttemptState.uppercase()

        if (controller == "LEASED" && job == "LEASED" && attempt == "LEASED" &&
            snapshot.transactionId == null &&
            snapshot.sendStartedAt == null &&
            snapshot.sentAt == null
        ) {
            return GatewayCancellationDecision.CANCEL_BEFORE_SEND
        }

        if (controller == "AWAITING_REPLY" && job == "AWAITING_REPLY" &&
            attempt == "AWAITING_REPLY" &&
            !snapshot.transactionId.isNullOrBlank() &&
            !snapshot.sendStartedAt.isNullOrBlank() &&
            !snapshot.sentAt.isNullOrBlank()
        ) {
            return GatewayCancellationDecision.REQUIRE_CONTROLLER_CLOSURE_AND_COOLDOWN
        }

        // SENDING without a confirmed callback, mismatched states, missing
        // attempt evidence and already ambiguous work must never be released.
        return GatewayCancellationDecision.BLOCK_AND_QUARANTINE
    }
}
