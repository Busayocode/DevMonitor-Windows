export type AttemptState = "LEASED" | "SENDING" | "AWAITING_REPLY" | "AMBIGUOUS" | "COMPLETED" | "REJECTED";
export type CancellationOutcome = "CANCELLED_BEFORE_SEND" | "CANCELLED_AFTER_SEND" | "BLOCKED_QUARANTINED";

export function replyTimeoutSeconds(profile: string): number {
  return /partner/i.test(profile) ? 180 : 120;
}

/** Classify cancellation only from persisted attempt/job state and SMS_SENT evidence. */
export function classifyCancellation(
  attempt: { state: AttemptState; sendStartedAt?: string | null; smsSentAt?: string | null },
  jobState: AttemptState,
): CancellationOutcome | null {
  if (attempt.state === "LEASED" && jobState === "LEASED" && !attempt.sendStartedAt && !attempt.smsSentAt) {
    return "CANCELLED_BEFORE_SEND";
  }
  if (attempt.state === "AWAITING_REPLY" && jobState === "AWAITING_REPLY" && !!attempt.sendStartedAt && !!attempt.smsSentAt) {
    return "CANCELLED_AFTER_SEND";
  }
  if (attempt.state === "SENDING" || attempt.state === "AMBIGUOUS" || jobState === "SENDING" || jobState === "AMBIGUOUS") {
    return "BLOCKED_QUARANTINED";
  }
  return null;
}

export function cooldownExpiry(nowMs: number): string {
  return new Date(nowMs + 4 * 60 * 1000).toISOString();
}

export function maySafelyRetryExpiredAttempt(state: AttemptState, sendStartedAt?: string | null, smsSentAt?: string | null): boolean {
  return state === "LEASED" && !sendStartedAt && !smsSentAt;
}

export function requiresGatewayQuarantine(state: AttemptState): boolean {
  return state === "SENDING" || state === "AWAITING_REPLY" || state === "AMBIGUOUS";
}
