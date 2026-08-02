import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

import { docsCategories, getArticle, getCategory } from "@/app/lib/docs/registry";
import { DOC_BLOCK_TYPES, ILLUSTRATION_NAMES } from "@/app/lib/docs/types";

const LOCALES = ["en", "ja"] as const;

type Messages = Record<string, unknown>;

function readMessages(locale: string): Messages {
    return JSON.parse(readFileSync(join(process.cwd(), "messages", locale, "docs.json"), "utf8"));
}

function articleEntry(messages: Messages, namespace: string, slug: string): Record<string, unknown> | null {
    const category = messages[namespace] as Record<string, unknown> | undefined;
    if (!category) return null;
    const articles = category.articles as Record<string, unknown> | undefined;
    if (!articles) return null;
    return (articles[slug] as Record<string, unknown> | undefined) ?? null;
}

function collectBlocks(messages: Messages): { path: string; block: Record<string, unknown> }[] {
    const found: { path: string; block: Record<string, unknown> }[] = [];
    for (const category of docsCategories) {
        for (const article of category.articles) {
            const entry = articleEntry(messages, category.namespace, article.slug);
            const blocks = entry?.blocks;
            if (!Array.isArray(blocks)) continue;
            blocks.forEach((block, index) => {
                found.push({
                    path: `${category.namespace}.articles.${article.slug}.blocks[${index}]`,
                    block: block as Record<string, unknown>,
                });
            });
        }
    }
    return found;
}

describe("docs registry and content", () => {
    it("gives every registry article its own message keys in every locale", () => {
        const missing: string[] = [];
        for (const locale of LOCALES) {
            const messages = readMessages(locale);
            for (const category of docsCategories) {
                const entry = messages[category.namespace] as Record<string, unknown> | undefined;
                for (const field of ["title", "description", "lead"]) {
                    if (typeof entry?.[field] !== "string") {
                        missing.push(`${locale}: ${category.namespace}.${field}`);
                    }
                }
                for (const article of category.articles) {
                    const found = articleEntry(messages, category.namespace, article.slug);
                    for (const field of ["title", "description"]) {
                        if (typeof found?.[field] !== "string") {
                            missing.push(`${locale}: ${category.namespace}.articles.${article.slug}.${field}`);
                        }
                    }
                    if (!Array.isArray(found?.blocks) || (found.blocks as unknown[]).length === 0) {
                        missing.push(`${locale}: ${category.namespace}.articles.${article.slug}.blocks`);
                    }
                }
            }
        }
        expect(missing).toEqual([]);
    });

    it("uses only slugs and namespaces that are unique across the tree", () => {
        const categorySlugs = docsCategories.map((category) => category.slug);
        const namespaces = docsCategories.map((category) => category.namespace);
        expect(new Set(categorySlugs).size).toBe(categorySlugs.length);
        expect(new Set(namespaces).size).toBe(namespaces.length);
        for (const category of docsCategories) {
            const slugs = category.articles.map((article) => article.slug);
            expect(new Set(slugs).size).toBe(slugs.length);
        }
    });

    it("points every card link at a docs page that exists", () => {
        const broken: string[] = [];
        for (const locale of LOCALES) {
            for (const { path, block } of collectBlocks(readMessages(locale))) {
                if (block.type !== "cards") continue;
                for (const item of block.items as { title: string; href?: string }[]) {
                    if (!item.href) continue;
                    const segments = item.href.split("/").filter(Boolean);
                    const resolved =
                        segments[0] === "docs" &&
                        (segments.length === 2
                            ? getCategory(segments[1]) !== undefined
                            : segments.length === 3 && getArticle(segments[1], segments[2]) !== undefined);
                    if (!resolved) broken.push(`${locale}: ${path} -> ${item.href}`);
                }
            }
        }
        expect(broken).toEqual([]);
    });

    it("uses only known block types and illustration names", () => {
        const invalid: string[] = [];
        for (const locale of LOCALES) {
            for (const { path, block } of collectBlocks(readMessages(locale))) {
                const type = block.type as string;
                if (!DOC_BLOCK_TYPES.has(type as never)) {
                    invalid.push(`${locale}: ${path} unknown type ${type}`);
                    continue;
                }
                if (type === "illustration" && !ILLUSTRATION_NAMES.includes(block.name as never)) {
                    invalid.push(`${locale}: ${path} unknown illustration ${String(block.name)}`);
                }
            }
        }
        expect(invalid).toEqual([]);
    });

    it("keeps every table row the same width as its header", () => {
        const malformed: string[] = [];
        for (const locale of LOCALES) {
            for (const { path, block } of collectBlocks(readMessages(locale))) {
                if (block.type !== "table") continue;
                const columns = (block.columns as string[]).length;
                (block.rows as string[][]).forEach((row, index) => {
                    if (row.length !== columns) {
                        malformed.push(`${locale}: ${path}.rows[${index}] has ${row.length} of ${columns}`);
                    }
                });
            }
        }
        expect(malformed).toEqual([]);
    });
});
