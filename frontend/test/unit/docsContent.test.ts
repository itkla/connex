import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

import { docsCategories, getArticle, getCategory, type DocCategory } from "@/app/lib/docs/registry";
import { DOC_BLOCK_TYPES, ILLUSTRATION_NAMES, type DocBlock } from "@/app/lib/docs/types";

const LOCALES = ["en", "ja"] as const;
const NON_CATEGORY_NAMESPACES = new Set(["DocsMeta", "DocsHome"]);
const CALLOUT_VARIANTS: ReadonlySet<string> = new Set(["note", "tip", "warning", "quirk"]);
const WARMTH_BANDS: ReadonlySet<string> = new Set(["hot", "warm", "cool", "cold"]);
const HEADING_LEVELS = [2, 3] as const;
const FEATURE_LIST_COLUMNS = [1, 2] as const;

type Messages = Record<string, unknown>;
type Block = Record<string, unknown>;

function readMessages(locale: string): Messages {
    return JSON.parse(readFileSync(join(process.cwd(), "messages", locale, "docs.json"), "utf8"));
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isFilledString(value: unknown): value is string {
    return typeof value === "string" && value.trim().length > 0;
}

function articleEntry(messages: Messages, namespace: string, slug: string): Record<string, unknown> | null {
    const category = messages[namespace];
    if (!isRecord(category)) return null;
    const articles = category.articles;
    if (!isRecord(articles)) return null;
    const entry = articles[slug];
    return isRecord(entry) ? entry : null;
}

function articleBlockLists(messages: Messages): { path: string; blocks: unknown[] }[] {
    return docsCategories.flatMap((category) =>
        category.articles.map((article) => {
            const entry = articleEntry(messages, category.namespace, article.slug);
            const blocks = entry?.blocks;
            return {
                path: `${category.namespace}.articles.${article.slug}.blocks`,
                blocks: Array.isArray(blocks) ? blocks : [],
            };
        }),
    );
}

function collectBlocks(messages: Messages): { path: string; block: Block }[] {
    return articleBlockLists(messages).flatMap(({ path, blocks }) =>
        blocks.map((block, index) => ({
            path: `${path}[${index}]`,
            block: isRecord(block) ? block : {},
        })),
    );
}

function requiredString(block: Block, field: string): string[] {
    return isFilledString(block[field]) ? [] : [`${field} must be a non-empty string`];
}

function optionalString(block: Block, field: string): string[] {
    return field in block && !isFilledString(block[field])
        ? [`${field} must be a non-empty string when present`]
        : [];
}

function requiredText(block: Block, field: string): string[] {
    const value = block[field];
    if (isFilledString(value)) return [];
    if (Array.isArray(value) && value.length > 0 && value.every(isFilledString)) return [];
    return [`${field} must be a non-empty string or an array of non-empty strings`];
}

function requiredMember(block: Block, field: string, allowed: ReadonlySet<string>): string[] {
    const value = block[field];
    if (typeof value === "string" && allowed.has(value)) return [];
    return [`${field} must be one of ${[...allowed].join("|")}, got ${JSON.stringify(value)}`];
}

function optionalNumericMember(block: Block, field: string, allowed: readonly number[]): string[] {
    if (!(field in block)) return [];
    const value = block[field];
    if (typeof value === "number" && allowed.includes(value)) return [];
    return [`${field} must be one of ${allowed.join("|")} when present, got ${JSON.stringify(value)}`];
}

function requiredItems(
    block: Block,
    fields: readonly string[],
    inspectItem?: (item: Block, index: number) => string[],
): string[] {
    const items = block.items;
    if (!Array.isArray(items) || items.length === 0) return ["items must be a non-empty array"];
    return items.flatMap((item, index) => {
        if (!isRecord(item)) return [`items[${index}] must be an object`];
        const missing = fields
            .filter((field) => !isFilledString(item[field]))
            .map((field) => `items[${index}].${field} must be a non-empty string`);
        return inspectItem ? [...missing, ...inspectItem(item, index)] : missing;
    });
}

function requiredStringGrid(block: Block): string[] {
    const columns = block.columns;
    const rows = block.rows;
    const issues: string[] = [];
    if (!Array.isArray(columns) || columns.length === 0 || !columns.every(isFilledString)) {
        issues.push("columns must be a non-empty array of non-empty strings");
    }
    if (!Array.isArray(rows) || rows.length === 0) {
        issues.push("rows must be a non-empty array");
        return issues;
    }
    rows.forEach((row, index) => {
        if (!Array.isArray(row) || row.length === 0 || !row.every((cell) => typeof cell === "string")) {
            issues.push(`rows[${index}] must be a non-empty array of strings`);
        }
    });
    return issues;
}

function cardItemIssues(item: Block, index: number): string[] {
    if (!("href" in item)) return [`items[${index}].href is missing, so the card renders as dead copy`];
    return isFilledString(item.href) ? [] : [`items[${index}].href must be a non-empty string`];
}

function warmthItemIssues(item: Block, index: number): string[] {
    const band = item.band;
    if (typeof band === "string" && WARMTH_BANDS.has(band)) return [];
    return [`items[${index}].band must be one of ${[...WARMTH_BANDS].join("|")}, got ${JSON.stringify(band)}`];
}

const BLOCK_PAYLOAD_VALIDATORS: Record<DocBlock["type"], (block: Block) => string[]> = {
    prose: (block) => requiredText(block, "text"),
    heading: (block) => [
        ...requiredString(block, "text"),
        ...optionalNumericMember(block, "level", HEADING_LEVELS),
    ],
    featureList: (block) => [
        ...optionalString(block, "title"),
        ...optionalNumericMember(block, "columns", FEATURE_LIST_COLUMNS),
        ...requiredItems(block, ["title", "description"]),
    ],
    callout: (block) => [
        ...optionalString(block, "title"),
        ...requiredMember(block, "variant", CALLOUT_VARIANTS),
        ...requiredText(block, "body"),
    ],
    steps: (block) => [...optionalString(block, "title"), ...requiredItems(block, ["title", "description"])],
    shortcuts: (block) => [...optionalString(block, "title"), ...requiredItems(block, ["keys", "action"])],
    faq: (block) => [...optionalString(block, "title"), ...requiredItems(block, ["question", "answer"])],
    cards: (block) => [
        ...optionalString(block, "title"),
        ...requiredItems(block, ["title", "description"], cardItemIssues),
    ],
    warmthLegend: (block) => [
        ...optionalString(block, "title"),
        ...requiredItems(block, ["label", "description"], warmthItemIssues),
    ],
    table: (block) => [...optionalString(block, "title"), ...requiredStringGrid(block)],
    illustration: (block) => [...requiredString(block, "name"), ...optionalString(block, "caption")],
};

function knownBlockType(block: Block): DocBlock["type"] | null {
    const type = block.type;
    if (typeof type !== "string" || !DOC_BLOCK_TYPES.has(type as DocBlock["type"])) return null;
    return type as DocBlock["type"];
}

function blockPayloadFailures(messages: Messages, locale: string): string[] {
    return collectBlocks(messages).flatMap(({ path, block }) => {
        const type = knownBlockType(block);
        if (type === null) return [];
        return BLOCK_PAYLOAD_VALIDATORS[type](block).map((issue) => `${locale}: ${path} ${issue}`);
    });
}

function articleContentFailures(messages: Messages, locale: string): string[] {
    const failures: string[] = [];
    for (const category of docsCategories) {
        const entry = messages[category.namespace];
        const fields = isRecord(entry) ? entry : {};
        for (const field of ["title", "description", "lead"]) {
            if (!isFilledString(fields[field])) failures.push(`${locale}: ${category.namespace}.${field}`);
        }
        for (const article of category.articles) {
            const found = articleEntry(messages, category.namespace, article.slug);
            const base = `${category.namespace}.articles.${article.slug}`;
            for (const field of ["title", "description"]) {
                if (!isFilledString(found?.[field])) failures.push(`${locale}: ${base}.${field}`);
            }
            const blocks = found?.blocks;
            if (!Array.isArray(blocks) || blocks.length === 0) failures.push(`${locale}: ${base}.blocks`);
        }
    }
    return failures;
}

function contentWithoutRegistryEntry(messages: Messages, locale: string): string[] {
    const namespaces = new Map(docsCategories.map((category) => [category.namespace, category]));
    const orphans: string[] = [];
    for (const namespace of Object.keys(messages)) {
        if (NON_CATEGORY_NAMESPACES.has(namespace)) continue;
        const category = namespaces.get(namespace);
        if (category === undefined) {
            orphans.push(`${locale}: ${namespace} has no registry category`);
            continue;
        }
        const entry = messages[namespace];
        const articles = isRecord(entry) ? entry.articles : undefined;
        if (!isRecord(articles)) continue;
        const slugs = new Set(category.articles.map((article) => article.slug));
        for (const slug of Object.keys(articles)) {
            if (!slugs.has(slug)) orphans.push(`${locale}: ${namespace}.articles.${slug} has no registry article`);
        }
    }
    return orphans;
}

function cardHrefFailure(href: string): string | null {
    if (!href.startsWith("/")) return "card links must be internal absolute paths";
    const segments = href.split("/").filter(Boolean);
    if (segments[0] !== "docs") return null;
    const resolved =
        segments.length === 2
            ? getCategory(segments[1]) !== undefined
            : segments.length === 3 && getArticle(segments[1], segments[2]) !== undefined;
    return resolved ? null : "no docs category or article in the registry answers this path";
}

function cardLinkFailures(messages: Messages, locale: string): string[] {
    return collectBlocks(messages).flatMap(({ path, block }) => {
        if (block.type !== "cards" || !Array.isArray(block.items)) return [];
        return block.items.flatMap((item, index) => {
            if (!isRecord(item) || !isFilledString(item.href)) return [];
            const failure = cardHrefFailure(item.href);
            return failure === null ? [] : [`${locale}: ${path}.items[${index}] -> ${item.href}: ${failure}`];
        });
    });
}

function unknownBlockIdentifiers(messages: Messages, locale: string): string[] {
    return collectBlocks(messages).flatMap(({ path, block }) => {
        const type = knownBlockType(block);
        if (type === null) return [`${locale}: ${path} unknown type ${JSON.stringify(block.type)}`];
        if (type !== "illustration") return [];
        const name = block.name;
        if (typeof name === "string" && (ILLUSTRATION_NAMES as readonly string[]).includes(name)) return [];
        return [`${locale}: ${path} unknown illustration ${JSON.stringify(name)}`];
    });
}

function tableWidthFailures(messages: Messages, locale: string): string[] {
    return collectBlocks(messages).flatMap(({ path, block }) => {
        if (block.type !== "table" || !Array.isArray(block.columns) || !Array.isArray(block.rows)) return [];
        const columns = block.columns.length;
        return block.rows.flatMap((row, index) =>
            Array.isArray(row) && row.length !== columns
                ? [`${locale}: ${path}.rows[${index}] has ${row.length} of ${columns}`]
                : [],
        );
    });
}

function blockShapeSignature(block: Block): string {
    const type = knownBlockType(block) ?? "unknown";
    if (type === "cards") {
        const items = Array.isArray(block.items) ? block.items : [];
        const hrefs = items.map((item) =>
            isRecord(item) && typeof item.href === "string" ? item.href : "(no href)",
        );
        return `cards[${hrefs.join(" ")}]`;
    }
    if (type === "table") {
        const columns = Array.isArray(block.columns) ? block.columns.length : -1;
        const rows = Array.isArray(block.rows) ? block.rows.length : -1;
        return `table[${columns} columns x ${rows} rows]`;
    }
    return type;
}

function blockShapeFailures(
    reference: { messages: Messages; locale: string },
    translated: { messages: Messages; locale: string },
): string[] {
    const translatedBlocks = new Map(
        articleBlockLists(translated.messages).map(({ path, blocks }) => [path, blocks]),
    );
    return articleBlockLists(reference.messages).flatMap(({ path, blocks }) => {
        const other = translatedBlocks.get(path) ?? [];
        const label = `${reference.locale}/${translated.locale}: ${path}`;
        if (blocks.length !== other.length) {
            return [`${label} has ${blocks.length} blocks in ${reference.locale} and ${other.length} in ${translated.locale}`];
        }
        return blocks.flatMap((block, index) => {
            const left = blockShapeSignature(isRecord(block) ? block : {});
            const right = blockShapeSignature(isRecord(other[index]) ? (other[index] as Block) : {});
            return left === right ? [] : [`${label}[${index}] is ${left} in ${reference.locale} and ${right} in ${translated.locale}`];
        });
    });
}

function categoriesWithoutArticles(categories: DocCategory[]): string[] {
    return categories.filter((category) => category.articles.length === 0).map((category) => category.slug);
}

describe("docs registry and content", () => {
    it("gives every registry article its own message keys in every locale", () => {
        const missing = LOCALES.flatMap((locale) => articleContentFailures(readMessages(locale), locale));
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

    it("keeps every category populated so no landing page ships empty", () => {
        expect(categoriesWithoutArticles(docsCategories)).toEqual([]);
    });

    it("leaves no article or namespace content behind that the registry no longer serves", () => {
        const orphans = LOCALES.flatMap((locale) => contentWithoutRegistryEntry(readMessages(locale), locale));
        expect(orphans).toEqual([]);
    });

    it("points every card link at a docs page that exists", () => {
        const broken = LOCALES.flatMap((locale) => cardLinkFailures(readMessages(locale), locale));
        expect(broken).toEqual([]);
    });

    it("uses only known block types and illustration names", () => {
        const invalid = LOCALES.flatMap((locale) => unknownBlockIdentifiers(readMessages(locale), locale));
        expect(invalid).toEqual([]);
    });

    it("gives every block the payload its own type requires", () => {
        const invalid = LOCALES.flatMap((locale) => blockPayloadFailures(readMessages(locale), locale));
        expect(invalid).toEqual([]);
    });

    it("keeps every table row the same width as its header", () => {
        const malformed = LOCALES.flatMap((locale) => tableWidthFailures(readMessages(locale), locale));
        expect(malformed).toEqual([]);
    });

    it("keeps the block sequence, card links, and table dimensions identical across locales", () => {
        const failures = blockShapeFailures(
            { messages: readMessages("en"), locale: "en" },
            { messages: readMessages("ja"), locale: "ja" },
        );
        expect(failures).toEqual([]);
    });
});

describe("docs content validators", () => {
    const invalidBlocks: [string, Block, string][] = [
        ["prose without text", { type: "prose" }, "text must be a non-empty string"],
        ["prose with a blank paragraph", { type: "prose", text: ["  "] }, "text must be a non-empty string"],
        ["heading without text", { type: "heading" }, "text must be a non-empty string"],
        ["heading at level 7", { type: "heading", text: "Overview", level: 7 }, "level must be one of 2|3"],
        [
            "featureList item without a description",
            { type: "featureList", items: [{ title: "Warmth" }] },
            "items[0].description must be a non-empty string",
        ],
        [
            "featureList with three columns",
            { type: "featureList", columns: 3, items: [{ title: "Warmth", description: "Signal" }] },
            "columns must be one of 1|2",
        ],
        ["featureList without items", { type: "featureList", items: [] }, "items must be a non-empty array"],
        [
            "callout with an unknown variant",
            { type: "callout", variant: "dangerous", body: "Careful" },
            "variant must be one of note|tip|warning|quirk",
        ],
        ["callout without a body", { type: "callout", variant: "note" }, "body must be a non-empty string"],
        ["steps without items", { type: "steps" }, "items must be a non-empty array"],
        [
            "shortcuts item without keys",
            { type: "shortcuts", items: [{ action: "Open search" }] },
            "items[0].keys must be a non-empty string",
        ],
        [
            "faq item without an answer",
            { type: "faq", items: [{ question: "Why?" }] },
            "items[0].answer must be a non-empty string",
        ],
        [
            "cards item without an href",
            { type: "cards", items: [{ title: "Warmth", description: "Signal" }] },
            "items[0].href is missing",
        ],
        [
            "cards item with an empty href",
            { type: "cards", items: [{ title: "Warmth", description: "Signal", href: "" }] },
            "items[0].href must be a non-empty string",
        ],
        [
            "warmthLegend with an unknown band",
            { type: "warmthLegend", items: [{ band: "lukewarm", label: "Lukewarm", description: "Maybe" }] },
            "items[0].band must be one of hot|warm|cool|cold",
        ],
        [
            "table without columns",
            { type: "table", columns: [], rows: [["a"]] },
            "columns must be a non-empty array",
        ],
        [
            "table with a numeric cell",
            { type: "table", columns: ["Field"], rows: [[3]] },
            "rows[0] must be a non-empty array of strings",
        ],
        ["illustration without a name", { type: "illustration" }, "name must be a non-empty string"],
        [
            "illustration with a blank caption",
            { type: "illustration", name: "warmth-scale", caption: "   " },
            "caption must be a non-empty string when present",
        ],
        [
            "block title that is only whitespace",
            { type: "steps", title: " ", items: [{ title: "One", description: "Do it" }] },
            "title must be a non-empty string when present",
        ],
    ];

    it.each(invalidBlocks)("rejects a %s", (_label, block, expected) => {
        const type = knownBlockType(block);
        expect(type).not.toBeNull();
        const issues = BLOCK_PAYLOAD_VALIDATORS[type as DocBlock["type"]](block);
        expect(issues.join(" | ")).toContain(expected);
    });

    it("accepts the shapes the reader itself accepts", () => {
        const valid: Block[] = [
            { type: "prose", text: ["One", "Two"] },
            { type: "heading", text: "Overview", level: 3 },
            { type: "featureList", columns: 1, items: [{ title: "Warmth", description: "Signal" }] },
            { type: "callout", variant: "quirk", body: "Careful" },
            { type: "illustration", name: "warmth-scale" },
        ];
        expect(valid.flatMap((block) => BLOCK_PAYLOAD_VALIDATORS[knownBlockType(block) as DocBlock["type"]](block))).toEqual([]);
    });

    it("flags a card link that no registry entry answers", () => {
        expect(cardHrefFailure("/docs/getting-started/quickstart")).toBeNull();
        expect(cardHrefFailure("/docs/getting-started")).toBeNull();
        expect(cardHrefFailure("/docs/getting-started/ghost-article")).toContain("no docs category or article");
        expect(cardHrefFailure("/docs/ghost-category")).toContain("no docs category or article");
        expect(cardHrefFailure("https://example.com")).toContain("internal absolute paths");
    });

    it("leaves internal routes outside the docs tree for their own owners to verify", () => {
        expect(cardHrefFailure("/pricing")).toBeNull();
        expect(cardHrefFailure("/records/companies")).toBeNull();
    });

    it("flags an empty category", () => {
        expect(
            categoriesWithoutArticles([{ slug: "ghost", namespace: "DocsGhost", icon: () => null, articles: [] }]),
        ).toEqual(["ghost"]);
    });

    it("flags content that no registry entry serves", () => {
        const messages = readMessages("en");
        const namespace = docsCategories[0].namespace;
        const category = messages[namespace];
        const articles = isRecord(category) ? category.articles : undefined;
        const mutated: Messages = {
            ...messages,
            DocsGhost: { title: "Ghost", description: "Ghost", lead: "Ghost", articles: {} },
            [namespace]: {
                ...(isRecord(category) ? category : {}),
                articles: { ...(isRecord(articles) ? articles : {}), "ghost-article": { blocks: [] } },
            },
        };
        expect(contentWithoutRegistryEntry(mutated, "en")).toEqual([
            `en: ${namespace}.articles.ghost-article has no registry article`,
            "en: DocsGhost has no registry category",
        ]);
    });

    it("flags a locale whose block shape drifts from the reference", () => {
        const en = readMessages("en");
        const shortened = structuredClone(en);
        const dropped = structuredClone(en);
        const retyped = structuredClone(en);
        const target = articleBlockLists(en).find(({ blocks }) =>
            blocks.some((block) => isRecord(block) && block.type === "cards"),
        );
        expect(target).toBeDefined();
        const [namespace, , slug] = (target as { path: string }).path.split(".");
        const blocksOf = (messages: Messages): unknown[] => {
            const entry = articleEntry(messages, namespace, slug);
            return Array.isArray(entry?.blocks) ? entry.blocks : [];
        };
        blocksOf(shortened).pop();
        const cardsIndex = blocksOf(dropped).findIndex((block) => isRecord(block) && block.type === "cards");
        const cardsBlock = blocksOf(dropped)[cardsIndex];
        if (isRecord(cardsBlock) && Array.isArray(cardsBlock.items)) cardsBlock.items.pop();
        const retypedBlock = blocksOf(retyped).find((block) => isRecord(block) && block.type !== "prose");
        expect(retypedBlock).toBeDefined();
        if (isRecord(retypedBlock)) retypedBlock.type = "prose";

        const reference = { messages: en, locale: "en" };
        expect(blockShapeFailures(reference, { messages: shortened, locale: "ja" })).not.toEqual([]);
        expect(blockShapeFailures(reference, { messages: dropped, locale: "ja" })).not.toEqual([]);
        expect(blockShapeFailures(reference, { messages: retyped, locale: "ja" })).not.toEqual([]);
        expect(blockShapeFailures(reference, { messages: structuredClone(en), locale: "ja" })).toEqual([]);
    });

    it("flags a table whose rows drift from its header", () => {
        const messages = readMessages("en");
        const mutated = structuredClone(messages);
        const target = articleBlockLists(mutated).find(({ blocks }) =>
            blocks.some((block) => isRecord(block) && block.type === "table"),
        );
        expect(target).toBeDefined();
        for (const block of (target as { blocks: unknown[] }).blocks) {
            if (isRecord(block) && block.type === "table" && Array.isArray(block.rows)) {
                const row = block.rows[0];
                if (Array.isArray(row)) row.push("extra");
                break;
            }
        }
        expect(tableWidthFailures(mutated, "en")).not.toEqual([]);
        expect(tableWidthFailures(messages, "en")).toEqual([]);
    });

    it("flags a blank article field", () => {
        const messages = readMessages("en");
        const mutated = structuredClone(messages);
        const category = mutated[docsCategories[0].namespace];
        if (isRecord(category)) category.lead = "   ";
        expect(articleContentFailures(mutated, "en")).toEqual([`en: ${docsCategories[0].namespace}.lead`]);
    });
});
