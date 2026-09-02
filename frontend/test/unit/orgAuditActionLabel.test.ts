import { readFileSync } from "node:fs";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { orgAuditActionKey, titleCaseAction } from "@/app/lib/orgAuditActionLabel";
import { loadVocabularyModel, scopeCovers } from "@/lint/vocabulary.mjs";

const LOCALES = ["en", "ja"] as const;
const BACKEND_SOURCE = path.resolve(process.cwd(), "..", "backend", "src", "main", "java");

/**
 * The audit actions `/api/orgs/{orgId}/audit` can return, and therefore the actions
 * `OrgAuditPanel` has to be able to name.
 *
 * `AuditLogMapper.findRecentByOrg` selects `org_id = :orgId AND workspace_id IS NULL`, so a row
 * reaches this panel only when its audit write left the workspace scope null and set an org scope.
 * Two backend shapes do that:
 *
 * - `auditService.record*(action, "organization", …)` — the entity-typed form, which
 *   `AuditService.writeUnchecked` scopes to the org and nulls the workspace;
 * - `auditService.record*Scoped(action, …, workspaceId, orgId, …)` called with a null workspace and
 *   a non-null org: the teardown, workspace-export and support-bundle services pass a literal null,
 *   and `SecretStore`/`SecretStoreLifecycleService` pass null for organization-scoped secrets.
 *
 * `AuditService.sensitiveAction` then rewrites any unlisted `secret_store.*` action to
 * `secret_store.operation` before the rows leave the service, so that alias is reachable too.
 *
 * Deliberately absent: `ai.llm.call` and `org.ai_budget.save` (both scoped with a primitive `int`
 * workspace id), `auth.logout` (its org id is null whenever its workspace id is), and
 * `user.connection.*` (scoped null/null, so `org_id` never matches).
 *
 * The tests below hold this list against the backend sources and against the message catalogs; the
 * `"organization"`-entity form is re-derived mechanically. An action added through the explicit
 * scope form still needs a human to add it here.
 */
const ORG_PLANE_AUDIT_ACTIONS = [
    "appi.incident.create",
    "appi.incident.update",
    "appi.subject_request.create",
    "appi.subject_request.disclosure",
    "appi.subject_request.update",
    "org.ai_provider.revoke",
    "org.ai_provider.save",
    "org.ai_provider.zdr_attest",
    "org.allowed_domain.add",
    "org.allowed_domain.remove",
    "org.create",
    "org.federated_identity.link",
    "org.login.federated_link",
    "org.member.founding_owner",
    "org.member.remove",
    "org.member.set",
    "org.rename",
    "org.sso_config.save",
    "org.sso_user.provision",
    "org.support_bundle.completed",
    "org.support_bundle.download",
    "org.workspace.create",
    "org.workspace.export",
    "org.workspace_member.sso_provision",
    "secret_store.diagnostics.read",
    "secret_store.operation",
    "secret_store.secret.rewrap",
    "secret_store.secret.rewrap_failed",
    "secret_store.secret.use",
    "secret_store.secret.use_failed",
    "tenant.organization.teardown",
    "tenant.workspace.teardown",
] as const;

function messagesFor(locale: (typeof LOCALES)[number]): Record<string, unknown> {
    const raw = readFileSync(path.join(process.cwd(), "messages", locale, "organization.json"), "utf8");
    return { OrgAudit: (JSON.parse(raw) as Record<string, Record<string, unknown>>).OrgAudit };
}

function sentence(locale: (typeof LOCALES)[number], action: string): string | null {
    const messages = messagesFor(locale);
    const key = orgAuditActionKey(messages, action);
    if (key === null) return null;
    let node: unknown = (messages.OrgAudit as Record<string, unknown>).action;
    for (const part of key.replace(/^action\./, "").split(".")) {
        node = (node as Record<string, unknown>)[part];
    }
    return typeof node === "string" ? node : null;
}

function mappedActionCodes(locale: (typeof LOCALES)[number]): string[] {
    const codes: string[] = [];
    const walk = (node: unknown, trail: string[]) => {
        if (typeof node === "string") {
            codes.push(trail.join("."));
            return;
        }
        if (typeof node !== "object" || node === null) return;
        for (const [key, value] of Object.entries(node)) walk(value, [...trail, key]);
    };
    walk((messagesFor(locale).OrgAudit as Record<string, unknown>).action, []);
    return codes.sort();
}

let backendSourcesPromise: Promise<string> | undefined;

function backendSources(): Promise<string> {
    backendSourcesPromise ??= readdir(BACKEND_SOURCE, { recursive: true })
        .then((entries) => Promise.all(
            entries
                .filter((entry) => entry.endsWith(".java"))
                .map((entry) => readFile(path.join(BACKEND_SOURCE, entry), "utf8")),
        ))
        .then((files) => files.join("\n"));
    return backendSourcesPromise;
}

describe("organization audit action labels", () => {
    it("names every audit action the org endpoint can return, in both languages", () => {
        for (const locale of LOCALES) {
            const missing = ORG_PLANE_AUDIT_ACTIONS.filter((action) => {
                const value = sentence(locale, action);
                return value === null || value.trim().length === 0;
            });

            expect(missing, `messages/${locale}/organization.json needs OrgAudit.action entries`).toEqual([]);
        }
    });

    it("carries no message for an action the org endpoint cannot return", () => {
        for (const locale of LOCALES) {
            expect(mappedActionCodes(locale)).toEqual([...ORG_PLANE_AUDIT_ACTIONS].sort());
        }
    });

    it("holds every listed action against a real backend audit action literal", async () => {
        const source = await backendSources();
        const stale = ORG_PLANE_AUDIT_ACTIONS.filter((action) => !source.includes(`"${action}"`));

        expect(stale, "these actions no longer exist in the backend; drop or rename them").toEqual([]);
    });

    it("re-derives the entity-typed org-plane actions from the backend", async () => {
        const source = await backendSources();
        const calls = [...source.matchAll(/\.record([A-Za-z]*)\(\s*"([a-z][a-z0-9_.]*)"\s*,\s*"organization"/g)];
        const derived = new Set(
            calls.filter((match) => !match[1].endsWith("Scoped")).map((match) => match[2]),
        );
        const listed = new Set<string>(ORG_PLANE_AUDIT_ACTIONS);
        const unlisted = [...derived].filter((action) => !listed.has(action)).sort();

        expect(derived.size).toBeGreaterThan(0);
        expect(unlisted, "a new org-scoped audit action needs a sentence in OrgAudit.action").toEqual([]);
    });

    it("keeps every action sentence clear of the banned vocabulary", () => {
        const model = loadVocabularyModel();
        const offenders: string[] = [];
        for (const locale of LOCALES) {
            for (const action of ORG_PLANE_AUDIT_ACTIONS) {
                const value = sentence(locale, action) ?? "";
                for (const term of model.terms) {
                    if (term.locale === "ja" && locale !== "ja") continue;
                    if (!scopeCovers(term.scope, "organization.json", "OrgAudit")) continue;
                    if (new RegExp(term.pattern.source, term.pattern.flags).test(value)) {
                        offenders.push(`${locale}/${action}: ${term.term}`);
                    }
                }
            }
        }

        expect(offenders, "docs/PRODUCT.md §4 bans these on product surfaces").toEqual([]);
    });

    it("falls back to sentence-cased words, never a raw code or a key path", () => {
        const messages = messagesFor("en");

        expect(orgAuditActionKey(messages, "org.brand_new.thing")).toBeNull();
        expect(titleCaseAction("org.brand_new.thing")).toBe("Org brand new thing");
        expect(titleCaseAction("org.brand_new.thing")).not.toContain(".");
    });

    it("does not resolve an action code that lands on an intermediate node", () => {
        const messages = messagesFor("en");

        expect(orgAuditActionKey(messages, "org.member.set")).toBe("action.org.member.set");
        expect(orgAuditActionKey(messages, "org.member")).toBeNull();
        expect(orgAuditActionKey(messages, "org")).toBeNull();
        expect(titleCaseAction("org.member")).toBe("Org member");
    });

    it("resolves a leaf that shares its parent with deeper codes", () => {
        const messages = messagesFor("en");

        expect(orgAuditActionKey(messages, "secret_store.operation")).toBe("action.secret_store.operation");
        expect(orgAuditActionKey(messages, "secret_store.secret.use")).toBe("action.secret_store.secret.use");
        expect(orgAuditActionKey(messages, "secret_store.secret")).toBeNull();
    });
});
