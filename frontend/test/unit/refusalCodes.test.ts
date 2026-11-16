import { createTranslator } from "next-intl";
import { describe, expect, it } from "vitest";

import { ApiError } from "@/app/lib/api";
import { userMessageFor } from "@/app/lib/errorMessages";
import en from "@/messages/en/errors.json";
import ja from "@/messages/ja/errors.json";

const refusals = [
    ["WORKSPACE_LAST_OWNER", "workspaceLastOwner"],
    ["ORG_LAST_OWNER", "organizationLastOwner"],
    ["WORKSPACE_RECOVERY_OWNER_REQUIRED", "workspaceRecoveryOwnerRequired"],
    ["INVITE_EMAIL_DOMAIN_NOT_ALLOWED", "inviteEmailDomainNotAllowed"],
    ["LAST_PASSKEY_REQUIRED", "lastPasskeyRequired"],
    ["LAST_SUFFICIENT_PASSKEY_REQUIRED", "lastSufficientPasskeyRequired"],
    ["ORG_MEMBER_ELIGIBILITY_REQUIRED", "organizationMemberEligibilityRequired"],
    ["DISCLOSURE_IDENTITY_VERIFICATION_REQUIRED", "disclosureIdentityVerificationRequired"],
] as const;

describe.each(["en", "ja"] as const)("business refusals in %s", (locale) => {
    const messages = locale === "en" ? en : ja;
    const t = createTranslator({ locale, messages });

    it.each(refusals)("explains %s ahead of status and caller fallbacks", (code, key) => {
        const error = new ApiError("private backend details", 400, code);
        const message = userMessageFor(error, t, undefined, "ApiErrors.invalid");
        expect(t.has(`ApiErrors.${key}`)).toBe(true);
        expect(message?.description).toBe(t(`ApiErrors.${key}`));
        expect(message?.description).not.toMatch(/private backend details|try again|もう一度お試し/);
    });

    it("keeps unknown codes and backend details private", () => {
        const error = new ApiError("private backend details", 403, "NEW_PRIVATE_REASON");
        expect(userMessageFor(error, t)?.description).toBe(messages.ApiErrors.forbidden);
    });
});
