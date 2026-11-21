import { describe, expect, it } from "vitest";
import { classifyCancellation, cooldownExpiry, maySafelyRetryExpiredAttempt, replyTimeoutSeconds, requiresGatewayQuarantine } from "./safety";

describe("Gateway timing and cancellation safety", () => {
  it("keeps Standard at 120 seconds and Partner at 180 seconds", () => {
    expect(replyTimeoutSeconds("standard")).toBe(120);
    expect(replyTimeoutSeconds("Partner menu")).toBe(180);
  });
  it("starts the four-minute cooldown only after confirmed SMS_SENT", () => {
    expect(classifyCancellation({state:"AWAITING_REPLY",smsSentAt:"2026-11-21T00:00:00.000Z"},"AWAITING_REPLY")).toBe("CANCELLED_AFTER_SEND");
    expect(classifyCancellation({state:"LEASED"},"LEASED")).toBe("CANCELLED_BEFORE_SEND");
    expect(classifyCancellation({state:"SENDING"},"SENDING")).toBe("BLOCKED_QUARANTINED");
  });
  it("calculates a durable four-minute deadline", () => {
    expect(cooldownExpiry(Date.parse("2026-11-21T00:00:00.000Z"))).toBe("2026-11-21T00:04:00.000Z");
  });
  it("never automatically retries a possible-send state", () => {
    expect(maySafelyRetryExpiredAttempt("LEASED")).toBe(true);
    expect(maySafelyRetryExpiredAttempt("LEASED","sent-started")).toBe(false);
    expect(maySafelyRetryExpiredAttempt("SENDING")).toBe(false);
    expect(requiresGatewayQuarantine("AWAITING_REPLY")).toBe(true);
    expect(requiresGatewayQuarantine("AMBIGUOUS")).toBe(true);
  });
});
