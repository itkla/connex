import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { DEFAULT_CAPABILITIES } from "@/app/lib/api";
import { NO_NAV_ACCESS, resolveNavAccess } from "@/app/lib/navAccess";

const PANEL = "app/components/diagnostics/DiagnosticsPanel.tsx";
const NAV_BRIDGE = "app/components/actions/NavActionsBridge.tsx";
const SEED_ACTIONS = "app/lib/actions/seedActions.ts";
const SETTINGS_TABS = "app/components/settings/SettingsTabs.tsx";

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

describe("diagnostics is offered exactly where its permission holds", () => {
    it("offers diagnostics to a viewer holding WORKSPACE_SETTINGS", () => {
        expect(resolveNavAccess(DEFAULT_CAPABILITIES, ["WORKSPACE_SETTINGS"]).diagnostics).toBe(true);
    });

    it("offers it to a custom role holding the permission without being an administrator", () => {
        const permissions = ["PERSON_READ", "WORKSPACE_SETTINGS"];

        expect(resolveNavAccess(DEFAULT_CAPABILITIES, permissions).diagnostics).toBe(true);
    });

    it("hides it from a member without the permission the endpoints enforce", () => {
        expect(resolveNavAccess(DEFAULT_CAPABILITIES, ["PERSON_READ"]).diagnostics).toBe(false);
    });

    it("fails closed when permissions could not be resolved", () => {
        expect(NO_NAV_ACCESS.diagnostics).toBe(false);
        expect(resolveNavAccess(DEFAULT_CAPABILITIES, []).diagnostics).toBe(false);
    });

    it("gates the palette entry and the settings tab on that same resolved access", () => {
        expect(source(NAV_BRIDGE)).toContain("if (navAccess.diagnostics) {");
        expect(source(SEED_ACTIONS)).not.toContain("/settings/diagnostics");
        expect(source(SETTINGS_TABS)).toContain('usePermission("WORKSPACE_SETTINGS")');
    });
});
