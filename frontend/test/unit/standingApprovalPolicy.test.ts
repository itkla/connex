import { describe, expect, it, vi } from "vitest";

vi.mock("@/app/lib/api", () => ({ csrfHeader: vi.fn() }));

import { isStandingApprovalPolicyInvalidation, validStandingApprovalCaps } from "@/app/lib/standingApprovalPolicy";
import type { AiStandingApprovalPolicy } from "@/app/lib/types";

const policy: AiStandingApprovalPolicy = {
    provisioned: true, authoringEnabled: false, enabled: false, allowedTools: [],
    maximumDailyCap: 100, maximumPerRunCap: 10, stateVersion: "1", killEpoch: "1",
};

describe("standing approval organization controls", () => {
    it("accepts finite per-approval limits, including the maximum boundary", () => {
        expect(validStandingApprovalCaps(policy)).toBe(true);
        expect(validStandingApprovalCaps({ ...policy, maximumDailyCap: 10000, maximumPerRunCap: 10000 })).toBe(true);
    });
    it.each([[0, 0], [10001, 10], [10, 11], [1.5, 1], [100, NaN], [100, Infinity]])(
        "refuses invalid caps %s/%s without clamping", (maximumDailyCap, maximumPerRunCap) => {
            expect(validStandingApprovalCaps({ ...policy, maximumDailyCap, maximumPerRunCap })).toBe(false);
        },
    );
    it("accepts only the content-free administrative invalidation", () => {
        expect(isStandingApprovalPolicyInvalidation({ kind: "policy_changed" })).toBe(true);
        for (const value of [null, "policy_changed", {}, { kind: "mission_state" },
            { kind: "policy_changed", text: "private" }]) {
            expect(isStandingApprovalPolicyInvalidation(value)).toBe(false);
        }
    });
});
