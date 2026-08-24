import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { getArticle } from "@/app/lib/docs/registry";

const LOCALES = ["en", "ja"] as const;
const DOCS_NAMESPACE = "DocsGettingStarted";
const DOCS_ARTICLE_SLUG = "navigating-connex";

type Locale = (typeof LOCALES)[number];

function read(relativePath: string): string {
    return readFileSync(path.join(process.cwd(), relativePath), "utf8");
}

function readMessages(locale: Locale, file: string): Record<string, unknown> {
    const parsed: unknown = JSON.parse(read(path.join("messages", locale, file)));
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
        throw new Error(`messages/${locale}/${file} is not an object`);
    }
    return parsed as Record<string, unknown>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * The source of truth for the app's navigation areas: the body of `useSections` in `Sidebar.tsx`,
 * which is the only place a permanent sidebar section is declared. Reading the function body rather
 * than the whole file keeps the dynamic "Pinned views" and "Recent" groups — which appear only when
 * populated and are not navigation areas — out of the comparison.
 */
function sidebarSectionsSource(): string {
    const source = read(path.join("app", "components", "Sidebar.tsx"));
    const start = source.indexOf("function useSections(");
    if (start < 0) throw new Error("Sidebar.tsx no longer declares useSections");
    const end = source.indexOf("\n}\n", start);
    if (end < 0) throw new Error("could not find the end of useSections in Sidebar.tsx");
    return source.slice(start, end);
}

function sidebarSectionLabelKeys(): string[] {
    const keys = [...sidebarSectionsSource().matchAll(/label:\s*t\(["'](section[A-Za-z]+)["']\)/g)]
        .map((match) => match[1]);
    if (keys.length === 0) throw new Error("Sidebar.tsx declares no sidebar section labels");
    return keys;
}

function sidebarSectionLabels(locale: Locale): string[] {
    const sidebar = readMessages(locale, "common.json").CommonSidebar;
    if (!isRecord(sidebar)) throw new Error(`messages/${locale}/common.json has no CommonSidebar namespace`);
    return sidebarSectionLabelKeys().map((key) => {
        const label = sidebar[key];
        if (typeof label !== "string" || label.trim().length === 0) {
            throw new Error(`CommonSidebar.${key} is missing from messages/${locale}/common.json`);
        }
        return label;
    });
}

function articleProseTexts(locale: Locale): string[] {
    const docs = readMessages(locale, "docs.json");
    const namespace = docs[DOCS_NAMESPACE];
    if (!isRecord(namespace)) throw new Error(`docs.json has no ${DOCS_NAMESPACE} namespace`);
    const articles = namespace.articles;
    if (!isRecord(articles)) throw new Error(`${DOCS_NAMESPACE} has no articles`);
    const article = articles[DOCS_ARTICLE_SLUG];
    if (!isRecord(article)) throw new Error(`${DOCS_NAMESPACE} has no ${DOCS_ARTICLE_SLUG} article`);
    const blocks = article.blocks;
    if (!Array.isArray(blocks)) throw new Error(`${DOCS_ARTICLE_SLUG} has no blocks`);
    return blocks.flatMap((block) => {
        if (!isRecord(block) || block.type !== "prose") return [];
        const text = block.text;
        if (typeof text === "string") return [text];
        if (Array.isArray(text)) return text.filter((part): part is string => typeof part === "string");
        return [];
    });
}

/**
 * Parses the navigation areas a docs sentence enumerates.
 *
 * The contract both locales follow is one clause: a colon, the separated area names (comma, 読点, or
 * nakaguro), then the sentence's terminator. A trailing parenthetical qualifier on an area — like
 * "(when campaigns are enabled)" on the capability-gated Marketing section — is stripped, so the docs
 * may hedge conditional sections while the comparison still lands on the section label itself. Only
 * that clause is read, so prose edits elsewhere in the article cannot move this assertion.
 */
function enumeratedAreas(text: string): string[] {
    const separator = text.search(/[:：]/);
    if (separator < 0) return [];
    const rest = text.slice(separator + 1);
    const terminator = rest.search(/[.。]/);
    const clause = terminator < 0 ? rest : rest.slice(0, terminator);
    return clause
        .split(/[,、・]/)
        .map((part) => part.trim().replace(/^and\s+/i, "").replace(/\s*[（(][^（）()]*[）)]$/u, "").trim())
        .filter((part) => part.length > 0);
}

function documentedAreas(locale: Locale): string[] {
    for (const text of articleProseTexts(locale)) {
        const areas = enumeratedAreas(text);
        if (areas.length > 1) return areas;
    }
    throw new Error(`the ${DOCS_ARTICLE_SLUG} article in ${locale} enumerates no navigation areas`);
}

describe("in-app docs describe the real sidebar", () => {
    it("reads every permanent section the sidebar declares", () => {
        expect([...sidebarSectionLabelKeys()].sort()).toEqual([
            "sectionActivity",
            "sectionHelp",
            "sectionLibrary",
            "sectionMarketing",
            "sectionOverview",
            "sectionRecords",
            "sectionWorkspace",
        ]);
    });

    it.each(LOCALES)("enumerates exactly the shipped sidebar areas in %s", (locale) => {
        expect(new Set(documentedAreas(locale))).toEqual(new Set(sidebarSectionLabels(locale)));
    });

    it("documents the same number of areas in every locale", () => {
        const counts = LOCALES.map((locale) => documentedAreas(locale).length);

        expect(new Set(counts).size).toBe(1);
    });
});

/**
 * The permanent forwards for renamed documentation slugs.
 *
 * A docs slug is a route: it is bookmarked, linked from inside the product, and indexed, so a
 * rename that does not forward the old address breaks all three. Two renames have happened —
 * Rules to Workflows, and the warmth article's slug in #1340 WS4.6 — and both are declared in
 * `next.config.ts` beside each other. This reads that file as text rather than importing it,
 * because the config pulls in the bundler's own types; what matters is the pairing, and the
 * pairing is checkable either way.
 */
describe("renamed documentation slugs keep their old addresses working", () => {
    const config = readFileSync(path.join(process.cwd(), "next.config.ts"), "utf8");
    const pairs = [...config.matchAll(
        /source:\s*"(\/docs\/[^"]+)",\s*\n\s*destination:\s*"(\/docs\/[^"]+)",/g,
    )].map((match) => ({ source: match[1], destination: match[2] }));

    function slugs(docsPath: string): [string, string] {
        const [, , category, article] = docsPath.split("/");
        return [category, article];
    }

    it("forwards permanently, because the old slug is retired rather than busy", () => {
        expect(config).toContain("permanent: true");
    });

    it("declares every rename this suite knows about", () => {
        expect(pairs.map((pair) => pair.source).sort()).toEqual([
            "/docs/relationship-intelligence/warmth-and-temperature",
            "/docs/settings/rules-and-automation",
        ]);
    });

    it("forwards each retired slug to an article that exists", () => {
        const broken = pairs.filter((pair) => {
            const [category, article] = slugs(pair.destination);
            return getArticle(category, article) === undefined;
        });

        expect(broken.map((pair) => pair.destination)).toEqual([]);
    });

    it("forwards only from slugs the registry no longer serves", () => {
        const live = pairs.filter((pair) => {
            const [category, article] = slugs(pair.source);
            return getArticle(category, article) !== undefined;
        });

        expect(
            live.map((pair) => pair.source),
            "a redirect over a slug the registry still serves would shadow a real article",
        ).toEqual([]);
    });

    it("never forwards an address onto itself or onto another forward", () => {
        const sources = new Set(pairs.map((pair) => pair.source));

        expect(pairs.filter((pair) => sources.has(pair.destination))).toEqual([]);
    });
});
