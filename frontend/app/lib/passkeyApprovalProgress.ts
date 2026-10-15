import type { Passkey, PasskeyApprovalGap } from "@/app/lib/types";

/** The organizations where one of the account's own passkeys still needs approval (#1534). */
export type PasskeyApprovalProgress = {
    credentialId: string;
    /** The passkey's label, or null when the passkey list does not include it yet. */
    label: string | null;
    missingOrganizations: Array<{ orgId: number; orgName: string }>;
};

/**
 * Groups approval gaps by passkey, closest to complete first (#1534).
 *
 * One passkey has to be approved in every organization where the account is privileged, so
 * approvals spread across passkeys never add up: the first entry is the passkey every further code
 * should approve. A tie goes to the passkey used most recently, which is the likeliest to be at
 * hand, then to the newest.
 *
 * @param passkeys the account's passkeys, for their labels and use
 * @param gaps every organization each passkey still needs, as the requirements report them
 * @returns one entry per passkey with a gap, the recommended passkey first
 */
export function passkeyApprovalProgress(passkeys: Passkey[], gaps: PasskeyApprovalGap[]): PasskeyApprovalProgress[] {
    const byCredential = new Map<string, PasskeyApprovalProgress>();
    for (const gap of gaps) {
        const progress = byCredential.get(gap.credentialId) ?? {
            credentialId: gap.credentialId,
            label: passkeys.find((passkey) => passkey.credentialId === gap.credentialId)?.label ?? null,
            missingOrganizations: [],
        };
        if (!progress.missingOrganizations.some((organization) => organization.orgId === gap.orgId)) {
            progress.missingOrganizations.push({ orgId: gap.orgId, orgName: gap.orgName });
        }
        byCredential.set(gap.credentialId, progress);
    }
    const recency = (credentialId: string) => {
        const passkey = passkeys.find((candidate) => candidate.credentialId === credentialId);
        return {
            lastUsed: passkey?.lastUsedAt ? Date.parse(passkey.lastUsedAt) : Number.NEGATIVE_INFINITY,
            created: passkey ? Date.parse(passkey.createdAt) : Number.NEGATIVE_INFINITY,
        };
    };
    return [...byCredential.values()].sort((a, b) => {
        const missing = a.missingOrganizations.length - b.missingOrganizations.length;
        if (missing !== 0) return missing;
        const left = recency(a.credentialId);
        const right = recency(b.credentialId);
        if (left.lastUsed !== right.lastUsed) return right.lastUsed - left.lastUsed;
        if (left.created !== right.created) return right.created - left.created;
        return a.credentialId.localeCompare(b.credentialId);
    });
}
