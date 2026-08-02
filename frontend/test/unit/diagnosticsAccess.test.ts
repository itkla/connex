import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const PANEL = "app/components/diagnostics/DiagnosticsPanel.tsx";

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function diagnosticsMessage(locale: "en" | "ja", key: string): string {
    const parsed: unknown = JSON.parse(source(`messages/${locale}/workspace.json`));
    if (!isRecord(parsed) || !isRecord(parsed.TenantDiagnostics)) {
        throw new Error(`messages/${locale}/workspace.json has no TenantDiagnostics namespace`);
    }
    const message = parsed.TenantDiagnostics[key];
    if (typeof message !== "string") {
        throw new Error(`TenantDiagnostics.${key} is missing from messages/${locale}/workspace.json`);
    }
    return message;
}

describe("diagnostics denial", () => {
    it("classifies a refusal instead of surfacing the backend message", () => {
        const panel = source(PANEL);
        const classified = panel.indexOf("caught.status === 403");
        const rawMessage = panel.indexOf("setError(caught.message)");

        expect(classified).toBeGreaterThan(-1);
        expect(panel).toMatch(/setAccessDenied\(true\)/);
        if (rawMessage > -1) {
            expect(classified).toBeLessThan(rawMessage);
        }
    });

    it("renders the shared inline denial rather than a hand-rolled error card", () => {
        const panel = source(PANEL);

        expect(panel).toContain('<AccessDenied variant="inline"');
        expect(panel).toContain('body={t("noAccess")}');
    });

    it("offers no retry on a refusal, which no number of retries can satisfy", () => {
        const panel = source(PANEL);
        const denial = panel.indexOf("if (accessDenied)");

        expect(denial).toBeGreaterThan(-1);
        expect(denial).toBeLessThan(panel.indexOf('t("retry")'));
        expect(denial).toBeLessThan(panel.indexOf("<MailDeliverabilitySection"));
    });

    it("keeps the retry affordance for a genuinely transient failure", () => {
        const panel = source(PANEL);

        expect(panel).toContain('t("retry")');
        expect(panel).toContain('t("loadFailed")');
    });

    it("gives the organization scope the same denial its sibling tabs use", () => {
        const panel = source(PANEL);

        expect(panel).toContain("NoAccessCard");
        expect(panel).toContain('scope === "organization"');
    });

    it("localizes human denial copy in both locales without naming the permission constant", () => {
        for (const locale of ["en", "ja"] as const) {
            const noAccess = diagnosticsMessage(locale, "noAccess");

            expect(noAccess).not.toContain("WORKSPACE_SETTINGS");
            expect(noAccess).not.toBe(diagnosticsMessage(locale, "loadFailed"));
        }
    });

    it("never leaks a backend permission constant into user-facing copy", () => {
        for (const locale of ["en", "ja"] as const) {
            expect(source(`messages/${locale}/workspace.json`)).not.toContain("WORKSPACE_SETTINGS");
        }
    });
});
