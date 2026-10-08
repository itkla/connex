import { describe, expect, it } from "vitest";

import {
    formatPasskeyApprovalCode,
    isCompletePasskeyApprovalCode,
    passkeyApprovalCodeSymbols,
} from "@/app/lib/passkeyApprovalCode";

describe("passkey approval code input", () => {
    it("groups what the member types the way codes are handed out", () => {
        expect(formatPasskeyApprovalCode("abcd")).toBe("ABCD");
        expect(formatPasskeyApprovalCode("abcde")).toBe("ABCD-E");
        expect(formatPasskeyApprovalCode("ABCD-")).toBe("ABCD");
        expect(formatPasskeyApprovalCode("abcd efgh-jkmn pqrs")).toBe("ABCD-EFGH-JKMN-PQRS");
    });

    it("reads O as zero and I or L as one, as the server does", () => {
        expect(passkeyApprovalCodeSymbols("oil0")).toBe("0110");
        expect(formatPasskeyApprovalCode("O1L2-I3O4")).toBe("0112-1304");
    });

    it("drops anything that is not a letter or digit and stops at one code", () => {
        expect(formatPasskeyApprovalCode(" abcd.efgh/jkmn:pqrs ")).toBe("ABCD-EFGH-JKMN-PQRS");
        expect(formatPasskeyApprovalCode("ABCD-EFGH-JKMN-PQRS-TVWX")).toBe("ABCD-EFGH-JKMN-PQRS");
    });

    it("is complete only with all sixteen symbols", () => {
        expect(isCompletePasskeyApprovalCode("ABCD-EFGH-JKMN-PQR")).toBe(false);
        expect(isCompletePasskeyApprovalCode("ABCD-EFGH-JKMN-PQRS")).toBe(true);
        expect(isCompletePasskeyApprovalCode("abcdefghjkmnpqrs")).toBe(true);
        expect(isCompletePasskeyApprovalCode("")).toBe(false);
    });
});
