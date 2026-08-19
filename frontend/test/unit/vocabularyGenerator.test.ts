import { describe, expect, it } from "vitest";

import {
    bannedListEntries,
    buildVocabularyModel,
    canonicalTerms,
    CURATED_DECISIONS,
    inflections,
    JAPANESE_RENDERINGS,
    loadVocabularyModel,
    overlappingCanonicalTerms,
    readProductGuide,
    scopeCovers,
    vocabularyItems,
    vocabularySection,
} from "@/lint/vocabulary.mjs";

const section = vocabularySection(readProductGuide());
const items = vocabularyItems(section);
const canonical = canonicalTerms(section);
const committed = loadVocabularyModel();

const itemIds = new Set(items.map((item) => `${item.locale}:${item.term}`));
const renderedIds = new Set(Object.values(JAPANESE_RENDERINGS).map((rendering) => `ja:${rendering.term}`));

function expression(pattern: { source: string; flags: string }): RegExp {
    return new RegExp(pattern.source, pattern.flags);
}

function bannedTerm(id: string) {
    const term = committed.terms.find((candidate) => candidate.id === id);
    if (!term) throw new Error(`the generated model no longer bans ${id}`);
    return term;
}

const GLOSSARY_ROW = "| Task ownership | **Assignee** | **担当者** | Assigned to |";
const GENERATOR_NOTE = "Generator note: where a banned term is a substring of a canonical term";

function mutate(replacement: string, target: string = GLOSSARY_ROW): string {
    const guide = readProductGuide();
    if (!guide.includes(target)) throw new Error(`docs/PRODUCT.md no longer contains ${target}`);
    return guide.replace(target, replacement);
}

describe("vocabulary generator", () => {
    it("reproduces the committed model from docs/PRODUCT.md §4", () => {
        expect(committed).toEqual(buildVocabularyModel(readProductGuide()));
    });

    it("accounts for every banned item §4 states", () => {
        const accounted = new Set([
            ...committed.terms.map((term) => term.id),
            ...committed.skipped.map((skip) => skip.id),
        ]);
        const unaccounted = items
            .filter((item) => !accounted.has(`${item.locale}:${item.term}`))
            .map((item) => `${item.source}: ${item.text}`);

        expect(items.length).toBeGreaterThan(0);
        expect(unaccounted).toEqual([]);
    });

    it("attributes every generated term and skip to §4", () => {
        const unattributable = [...committed.terms, ...committed.skipped]
            .filter((entry) => !itemIds.has(entry.id) && !renderedIds.has(entry.id))
            .map((entry) => entry.id);

        expect(unattributable).toEqual([]);
    });

    it("cites the §4 source of every generated term and skip", () => {
        for (const entry of [...committed.terms, ...committed.skipped]) {
            expect(entry.sources.length).toBeGreaterThan(0);
            for (const source of entry.sources) {
                expect(source).toMatch(/^§4 (glossary row "|banned-on-all-product-surfaces list)/);
            }
        }
    });

    it("gives every curated skip a one-line reason", () => {
        for (const skip of committed.skipped) {
            expect(skip.reason.length).toBeGreaterThan(20);
            expect(skip.reason).not.toContain("\n");
        }
        for (const decision of Object.values(CURATED_DECISIONS)) {
            expect(decision.reason.length).toBeGreaterThan(20);
        }
    });

    it("decides every §4 item that carries a qualifier", () => {
        const undecided = items
            .filter((item) => item.qualified && !Object.hasOwn(CURATED_DECISIONS, item.text))
            .map((item) => item.text);

        expect(undecided).toEqual([]);
    });

    it("keeps the terms #1323 names in the model", () => {
        for (const id of ["ja:ウォームパス", "ja:温かい経路", "ja:温度バンド", "ja:関係スコア", "ja:決定論的", "ja:テナント", "en:tenant", "en:deterministic"]) {
            expect(bannedTerm(id).scope).toBe("global");
        }
        expect(bannedTerm("ja:ノート").scope).toEqual({ namespaces: expect.arrayContaining(["activity.json"]) });
        expect(bannedTerm("ja:抑制").scope).toEqual({ namespaces: expect.arrayContaining(["campaigns.json"]) });
    });

    it("names the compliance surfaces on the statutory terms §4 allows there", () => {
        for (const id of ["en:data subject", "en:cease of use", "en:third-party provision"]) {
            expect(bannedTerm(id).allowFiles).toEqual(["legal.json", "organization.json#OrgDataRequests"]);
        }
    });

    it("excepts the canonical carriers of 温度 instead of dropping the ban", () => {
        const bare = bannedTerm("ja:温度");
        expect(bare.canonicalExceptions).toEqual(["温度帯", "温度感"]);
        expect(expression(bare.pattern).test("温度が下がっています")).toBe(true);
        expect(expression(bare.pattern).test("温度感の分布")).toBe(false);
        expect(expression(bare.pattern).test("温度帯")).toBe(false);
        expect(expression(bare.pattern).test("関係の温度感を読み取ります")).toBe(false);
    });

    it("excepts the Node.js proper noun without dropping the ban on node", () => {
        const term = bannedTerm("en:node");

        expect(expression(term.pattern).test("Node.js 18 or newer is required.")).toBe(false);
        expect(expression(term.pattern).test("ノードは Node.js で動きます")).toBe(false);
        expect(expression(term.pattern).test("Each node of the graph")).toBe(true);
        expect(expression(term.pattern).test("the nodes it traverses")).toBe(true);
        expect(expression(term.pattern).test("a Node in the network")).toBe(true);
    });

    it("keeps tenant banned everywhere the legal pages are not", () => {
        for (const id of ["en:tenant", "ja:テナント"]) {
            const term = bannedTerm(id);

            expect(term.allowFiles).toEqual(["legal.json"]);
            expect(term.scope).toBe("global");
        }
        expect(expression(bannedTerm("en:tenant").pattern).test("every tenant is isolated")).toBe(true);
        expect(expression(bannedTerm("ja:テナント").pattern).test("テナントの分離")).toBe(true);
    });

    it("detects substring overlaps in either direction", () => {
        expect(overlappingCanonicalTerms("関係の温度", ["温度感"], 2)).toEqual({
            leading: [],
            trailing: ["感"],
            canonicalExceptions: ["温度感"],
        });
        expect(overlappingCanonicalTerms("温度", ["紹介ルート"], 2)).toEqual({
            leading: [],
            trailing: [],
            canonicalExceptions: [],
        });
    });

    it("still matches its own term after the canonical exceptions are applied", () => {
        for (const term of committed.terms.filter((entry) => !entry.narrowed)) {
            expect(expression(term.pattern).test(term.term)).toBe(true);
        }
    });

    it("documents a reason for every narrowed pattern", () => {
        const reasons = new Map<string, string>();
        for (const item of items) {
            if (Object.hasOwn(CURATED_DECISIONS, item.text)) reasons.set(item.term, CURATED_DECISIONS[item.text].reason);
        }
        for (const rendering of Object.values(JAPANESE_RENDERINGS)) reasons.set(rendering.term, rendering.reason);

        const narrowed = committed.terms.filter((term) => term.narrowed);
        expect(narrowed.length).toBeGreaterThan(0);
        for (const term of narrowed) {
            expect(reasons.get(term.term), term.id).toBeTruthy();
        }
    });

    it("never bans and skips the same term", () => {
        const banned = new Set(committed.terms.map((term) => term.id));
        expect(committed.skipped.filter((skip) => banned.has(skip.id)).map((skip) => skip.id)).toEqual([]);
    });

    it("matches the inflections of an English term without accepting a bare stem", () => {
        expect(inflections("purge")).toEqual(expect.arrayContaining(["purge", "purges", "purged", "purging"]));
        expect(inflections("admitted")).toEqual(expect.arrayContaining(["admitted", "admits", "admitting"]));
        expect(inflections("admitted")).not.toContain("admit");
        expect(inflections("suppression")).toEqual(expect.arrayContaining(["suppression", "suppressed", "suppressing"]));
        expect(inflections("suppression")).not.toContain("suppress");
        expect(inflections("rewrap")).toEqual(expect.arrayContaining(["rewrapped", "rewrapping"]));
        expect(inflections("idempotency")).toContain("idempotencies");
    });

    it("fails closed on a glossary row that is not four cells", () => {
        expect(() => buildVocabularyModel(mutate("| Task ownership | **Assignee** | **担当者** |")))
            .toThrow(/glossary row with 3 cells/);
    });

    it("fails closed on a banned-list entry that names no term", () => {
        expect(() => buildVocabularyModel(mutate("`tenant` · nothing in particular ·", "`tenant` ·")))
            .toThrow(/without naming a term in backticks/);
    });

    it("reads every line of the banned-terms list, not only the first", () => {
        const extended = mutate("`preflight` · `idempotency`\n\n`sharding` · `quorum`", "`preflight` · `idempotency`");
        const texts = bannedListEntries(vocabularySection(extended)).map((entry) => entry.text);

        expect(texts).toContain("`tenant`");
        expect(texts).toContain("`sharding`");
    });

    it("reads a banned-terms line that carries no separator", () => {
        const extended = mutate("`preflight` · `idempotency`\n\n`quorum`", "`preflight` · `idempotency`");
        const texts = bannedListEntries(vocabularySection(extended)).map((entry) => entry.text);

        expect(texts).toContain("`quorum`");
        expect(texts).toContain("`hash`");
    });

    it("fails closed on a banned term stated below the list", () => {
        const stranded = mutate(`${GENERATOR_NOTE}\n\nAlso never say \`quorum\`.`, GENERATOR_NOTE);

        expect(() => bannedListEntries(vocabularySection(stranded)))
            .toThrow(/names a term in backticks below the banned-terms list/);
    });

    it("never matches a canonical term of the same locale", () => {
        const matched = committed.terms.flatMap((term) =>
            canonical[term.locale]
                .filter((entry) => expression(term.pattern).test(entry))
                .map((entry) => `${term.id} matches canonical "${entry}"`),
        );

        expect(matched).toEqual([]);
    });

    it("resolves every scope form", () => {
        expect(scopeCovers("global", "contacts.json", "Contacts")).toBe(true);
        expect(scopeCovers({ namespaces: ["contacts.json"] }, "contacts.json", "Contacts")).toBe(true);
        expect(scopeCovers({ namespaces: ["contacts.json"] }, "deals.json", "DealsPage")).toBe(false);
        expect(scopeCovers({ namespaces: ["workspace.json#WorkflowAuthoring"] }, "workspace.json", "WorkflowAuthoring")).toBe(true);
        expect(scopeCovers({ namespaces: ["workspace.json#WorkflowAuthoring"] }, "workspace.json", "WorkspaceSso")).toBe(false);
        expect(scopeCovers({ excludeNamespaces: ["contacts.json"] }, "contacts.json", "Contacts")).toBe(false);
        expect(scopeCovers({ excludeNamespaces: ["contacts.json"] }, "deals.json", "DealsPage")).toBe(true);
    });
});
