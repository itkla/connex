import { describe, expect, it } from "vitest";

import {
    AT_A_GLANCE_SURFACES,
    ALLOWED_SURFACES,
    atAGlanceEntries,
    BASELINE_HIGH_WATER_MARK,
    baselineEntries,
    describeViolation,
    EXCLUDED_SURFACES,
    isAllowedSurface,
    isExcludedSurface,
    loadBaseline,
    loadVocabularyModel,
    messageEntries,
    parseBaselineEntry,
    scanMessageCatalogs,
    scopeCovers,
} from "@/lint/vocabulary.mjs";

const model = loadVocabularyModel();
const violations = scanMessageCatalogs(model);
const current = baselineEntries(violations);
const baseline = loadBaseline();

const REGENERATE = "Regenerate it with `node scripts/generate-vocabulary.mjs` after editing docs/PRODUCT.md §4.";
const BURN_DOWN = "The baseline only shrinks: fix the copy and delete its entry from frontend/lint/vocabulary-baseline.json.";

/**
 * The banned-term matches a surface filter removes from the gate, proving the filter is
 * load-bearing rather than decorative.
 */
function suppressedMatches(inScope: (entry: { file: string; namespace: string }) => boolean): string[] {
    const compiled = model.terms.map((term) => ({ term, expression: new RegExp(term.pattern.source, term.pattern.flags) }));
    return messageEntries()
        .filter(inScope)
        .flatMap((entry) =>
            compiled
                .filter(({ term, expression }) =>
                    term.locale === entry.locale
                    && scopeCovers(term.scope, entry.file, entry.namespace)
                    && expression.test(entry.value))
                .map(({ term }) => `${entry.locale}/${entry.file}:${entry.keyPath}#${term.term}`),
        );
}

describe("message catalogue vocabulary", () => {
    it("reports no banned term outside the committed baseline", () => {
        const known = new Set(baseline);
        const added = violations
            .filter((violation) => !known.has(violation.entry))
            .map(describeViolation);

        expect(added, `${added.length} new banned term(s). ${BURN_DOWN} ${REGENERATE}`).toEqual([]);
    });

    it("holds no baseline entry that no longer violates", () => {
        const remaining = new Set(current);
        const stale = baseline.filter((entry) => !remaining.has(entry));

        expect(stale, `${stale.length} baseline entry(s) are fixed. ${BURN_DOWN}`).toEqual([]);
    });

    it("keeps the baseline sorted, deduplicated, and scoped to real message keys", () => {
        expect(baseline).toEqual([...new Set(baseline)].sort());

        const keys = new Set(messageEntries().map((entry) => `${entry.locale}/${entry.file}:${entry.keyPath}`));
        const orphaned = baseline
            .map(parseBaselineEntry)
            .filter((entry) => !keys.has(`${entry.locale}/${entry.file}:${entry.keyPath}`))
            .map((entry) => entry.keyPath);

        expect(orphaned).toEqual([]);
    });

    it("never grows past the committed high-water mark", () => {
        expect(baseline.length, `raise BASELINE_HIGH_WATER_MARK only in the commit that widens the rules. ${BURN_DOWN}`)
            .toBeLessThanOrEqual(BASELINE_HIGH_WATER_MARK);
    });

    it("scans the strings inside arrays, not only the object leaves", () => {
        const entries = messageEntries();
        const inArrays = entries.filter((entry) => entry.keyPath.includes("["));

        expect(inArrays.length).toBeGreaterThan(1000);
        expect(baseline.some((entry) => entry.includes("["))).toBe(true);
    });

    it("lets the phrase §4 restricts to one surface only shrink", () => {
        const glances = atAGlanceEntries();
        const added = glances.filter((entry) => !AT_A_GLANCE_SURFACES.includes(entry));
        const removed = AT_A_GLANCE_SURFACES.filter((entry) => !glances.includes(entry));

        expect(added, "§4 allows \"at a glance\" on one surface; do not add another").toEqual([]);
        expect(removed, "rewritten copy: drop these from AT_A_GLANCE_SURFACES").toEqual([]);
    });

    it("never baselines an allowlisted or out-of-scope surface", () => {
        const overlapping = baseline
            .map(parseBaselineEntry)
            .filter((entry) => isAllowedSurface(entry.file, entry.namespace) || isExcludedSurface(entry.file, entry.namespace))
            .map((entry) => `${entry.locale}/${entry.file}:${entry.keyPath}`);

        expect(overlapping).toEqual([]);
    });

    it("exempts the compliance surfaces, which would otherwise report statutory terms", () => {
        const suppressed = suppressedMatches((entry) => isAllowedSurface(entry.file, entry.namespace));

        expect(ALLOWED_SURFACES).toEqual(["legal.json", "organization.json#OrgDataRequests"]);
        expect(suppressed.length).toBeGreaterThan(0);
        expect(violations.filter((violation) => suppressed.includes(violation.entry))).toEqual([]);
    });

    it("leaves the workflow seam WS5 owns out of scope", () => {
        const suppressed = suppressedMatches((entry) => isExcludedSurface(entry.file, entry.namespace));

        expect(EXCLUDED_SURFACES).toContain("workflow-operations.json");
        expect(EXCLUDED_SURFACES).toContain("workspace.json#WorkspaceRules");
        expect(suppressed.length).toBeGreaterThan(0);
        expect(violations.filter((violation) => suppressed.includes(violation.entry))).toEqual([]);
    });

    it("baselines each banned term separately, so a second term on a flagged string still fails", () => {
        const perKey = new Map<string, number>();
        for (const entry of baseline.map(parseBaselineEntry)) {
            const key = `${entry.locale}/${entry.file}:${entry.keyPath}`;
            perKey.set(key, (perKey.get(key) ?? 0) + 1);
        }

        expect([...perKey.values()].some((count) => count > 1)).toBe(true);
        expect(baseline.every((entry) => entry.includes("#"))).toBe(true);
    });

    it("states the file, key path, term, and §4 row of every violation", () => {
        for (const violation of violations) {
            const described = describeViolation(violation);
            expect(described).toContain(violation.file);
            expect(described).toContain(violation.keyPath);
            expect(described).toContain(violation.term);
            expect(described).toContain("docs/PRODUCT.md §4");
        }
    });

    it("says nothing false about where records are shared", () => {
        const shared = new Map(messageEntries().map((entry) => [`${entry.locale}/${entry.file}:${entry.keyPath}`, entry.value]));
        const scopeClaims = [
            "contacts.json:ContactsNewContactDialog.description",
            "companies.json:CompaniesNewDialog.description",
            "deals.json:DealsNewDialog.description",
            "pipelines.json:PipelinesNewDialog.description",
        ];

        for (const key of scopeClaims) {
            expect(shared.get(`en/${key}`)).toContain("shared with everyone in this workspace");
            expect(shared.get(`ja/${key}`)).toContain("このワークスペースのメンバー全員と共有されます");
        }
        expect(shared.get("en/attachments.json:LibraryFilesLayout.description")).toBe("Manage your workspace's files");
        expect(shared.get("ja/attachments.json:LibraryFilesLayout.description")).toBe("ワークスペースのファイルを管理");
        expect(shared.get("en/dashboard.json:DashboardLayout.description")).toBe("Your workspace at a glance");
        expect(shared.get("ja/dashboard.json:DashboardLayout.description")).toBe("ワークスペースの状況をひと目で確認");

        for (const [key, value] of shared) {
            expect(value, key).not.toContain("all users of this organization");
        }
    });
});
