import { describe, expect, it } from "vitest";

import { passkeyApprovalProgress } from "@/app/lib/passkeyApprovalProgress";
import type { Passkey } from "@/app/lib/types";

function passkey(credentialId: string, label: string, lastUsedAt: string | null, createdAt = "2026-09-01T00:00:00Z"): Passkey {
    return { credentialId, label, transports: [], createdAt, lastUsedAt };
}

const laptop = passkey("cred-laptop", "Laptop", "2026-10-10T00:00:00Z");
const phone = passkey("cred-phone", "Phone", "2026-10-12T00:00:00Z");
const key = passkey("cred-key", "Security key", null, "2026-10-01T00:00:00Z");

describe("passkey approval progress", () => {
    it("recommends the passkey closest to complete, because approvals never add up across passkeys", () => {
        const progress = passkeyApprovalProgress([laptop, phone], [
            { credentialId: "cred-laptop", orgId: 2, orgName: "Beta" },
            { credentialId: "cred-phone", orgId: 1, orgName: "Acme" },
            { credentialId: "cred-phone", orgId: 2, orgName: "Beta" },
        ]);

        expect(progress.map(({ label, missingOrganizations }) => ({
            label,
            missing: missingOrganizations.map(({ orgName }) => orgName),
        }))).toEqual([
            { label: "Laptop", missing: ["Beta"] },
            { label: "Phone", missing: ["Acme", "Beta"] },
        ]);
    });

    it("breaks a tie toward the passkey used most recently, then the newest", () => {
        const gaps = [
            { credentialId: "cred-key", orgId: 1, orgName: "Acme" },
            { credentialId: "cred-laptop", orgId: 1, orgName: "Acme" },
            { credentialId: "cred-phone", orgId: 1, orgName: "Acme" },
        ];

        expect(passkeyApprovalProgress([laptop, phone, key], gaps).map(({ credentialId }) => credentialId))
            .toEqual(["cred-phone", "cred-laptop", "cred-key"]);
        expect(passkeyApprovalProgress(
            [passkey("cred-a", "A", null, "2026-10-01T00:00:00Z"), passkey("cred-b", "B", null, "2026-10-05T00:00:00Z")],
            [{ credentialId: "cred-a", orgId: 1, orgName: "Acme" }, { credentialId: "cred-b", orgId: 1, orgName: "Acme" }],
        ).map(({ credentialId }) => credentialId)).toEqual(["cred-b", "cred-a"]);
    });

    it("counts an organization once per passkey and keeps a passkey the list does not show yet", () => {
        const progress = passkeyApprovalProgress([laptop], [
            { credentialId: "cred-laptop", orgId: 1, orgName: "Acme" },
            { credentialId: "cred-laptop", orgId: 1, orgName: "Acme" },
            { credentialId: "cred-new", orgId: 1, orgName: "Acme" },
        ]);

        expect(progress).toEqual([
            { credentialId: "cred-laptop", label: "Laptop", missingOrganizations: [{ orgId: 1, orgName: "Acme" }] },
            { credentialId: "cred-new", label: null, missingOrganizations: [{ orgId: 1, orgName: "Acme" }] },
        ]);
    });

    it("lists nothing when no passkey has a gap", () => {
        expect(passkeyApprovalProgress([laptop, phone], [])).toEqual([]);
    });
});
