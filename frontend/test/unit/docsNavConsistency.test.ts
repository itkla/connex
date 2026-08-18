import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

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
    const keys = [...sidebarSectionsSource().matchAll(/label:\s*t\("(section[A-Za-z]+)"\)/g)]
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
 * The contract both locales follow is one clause: a colon, the comma-separated area names, then the
 * sentence's terminator. Only that clause is read, so prose edits elsewhere in the article — or in
 * the rest of the same sentence — cannot move this assertion.
 */
function enumeratedAreas(text: string): string[] {
    const separator = text.search(/[:：]/);
    if (separator < 0) return [];
    const rest = text.slice(separator + 1);
    const terminator = rest.search(/[.。]/);
    const clause = terminator < 0 ? rest : rest.slice(0, terminator);
    return clause
        .split(/[,、]/)
        .map((part) => part.trim().replace(/^and\s+/i, "").trim())
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
