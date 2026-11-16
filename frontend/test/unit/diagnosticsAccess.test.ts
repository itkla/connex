import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { DEFAULT_CAPABILITIES } from "@/app/lib/api";
import { NO_NAV_ACCESS, resolveNavAccess } from "@/app/lib/navAccess";
import { SETTINGS_ENTRIES } from "@/app/lib/settingsManifest";

const PANEL = "app/components/diagnostics/DiagnosticsPanel.tsx";
const SETTINGS_ACTIONS = "app/lib/actions/settingsNavigationActions.ts";
const SEED_ACTIONS = "app/lib/actions/seedActions.ts";

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function messageConstants(value: unknown): string[] {
    if (typeof value === "string") return value.match(/[A-Z]+_[A-Z_]+/g) ?? [];
    if (Array.isArray(value)) return value.flatMap((entry: unknown) => messageConstants(entry));
    if (isRecord(value)) return Object.values(value).flatMap(messageConstants);
    return [];
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

    it.each([
        "messages/en/workspace.json",
        "messages/ja/workspace.json",
        "messages/en/account.json",
        "messages/ja/account.json",
        "messages/en/errors.json",
        "messages/ja/errors.json",
        "../landing/messages/en/errors.json",
        "../landing/messages/ja/errors.json",
    ])("never leaks a backend permission constant into user-facing copy in %s", (file) => {
        const parsed: unknown = JSON.parse(source(file));
        expect(isRecord(parsed), file).toBe(true);
        expect(messageConstants(parsed), file).toEqual([]);
    });

    it("allows internal permission keys with localized business labels", () => {
        expect(messageConstants({
            WORKSPACE_SETTINGS: { action: "Manage workspace settings", description: "設定を管理できます。" },
        })).toEqual([]);
    });

    it.each([
        "Ask for WORKSPACE_SETTINGS permission.",
        "WORKSPACE_SETTINGSの権限が必要です。",
        "The request was refused: WORKSPACE_LAST_OWNER.",
    ])("rejects a raw constant in a nested message value: %s", (message) => {
        expect(messageConstants({ group: { items: [{ description: message }] } })).toHaveLength(1);
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

    it("gates the palette entry and the settings navigation on that same resolved access", () => {
        const destination = SETTINGS_ENTRIES.find(
            (entry) => entry.currentRoute === "/settings/workspace/audit-diagnostics",
        );

        expect(source(SETTINGS_ACTIONS)).toContain("reachable: (navAccess) => navAccess.diagnostics");
        expect(source(SEED_ACTIONS)).not.toContain("/settings/diagnostics");
        /**
         * The peer-tab strip that used to carry this gate went with its last two destinations in
         * #1340's final scope-group PR. What gates the destination now is the manifest, which the
         * settings navigation reads: the consolidated page is reachable by either of the two
         * permissions it merged, and a workspace administrator holds this one.
         */
        expect(destination?.access.permissions).toContain("WORKSPACE_SETTINGS");
        expect(destination?.access.permissionMatch).toBe("any");
    });
});
