import { getTranslations } from "next-intl/server";
import {
    DOC_BLOCK_TYPES,
    ILLUSTRATION_NAMES,
    type CardEntry,
    type DocBlock,
    type FaqEntry,
    type FeatureEntry,
    type IllustrationName,
    type ShortcutEntry,
    type StepEntry,
    type WarmthBand,
    type WarmthEntry,
} from "./types";
import { articleBlocksKey, type DocArticle, type DocCategory } from "./registry";

type RawRecord = Record<string, unknown>;

function isRecord(value: unknown): value is RawRecord {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asString(value: unknown): string | null {
    return typeof value === "string" && value.length > 0 ? value : null;
}

function asStringOrStringArray(value: unknown): string | string[] | null {
    if (asString(value)) return value as string;
    if (Array.isArray(value)) {
        const items = value.filter((item): item is string => typeof item === "string");
        return items.length > 0 ? items : null;
    }
    return null;
}

function mapEntries<T>(value: unknown, project: (entry: RawRecord) => T | null): T[] {
    if (!Array.isArray(value)) return [];
    return value.reduce<T[]>((acc, entry) => {
        if (!isRecord(entry)) return acc;
        const projected = project(entry);
        if (projected !== null) acc.push(projected);
        return acc;
    }, []);
}

const WARMTH_BANDS: ReadonlySet<WarmthBand> = new Set(["hot", "warm", "cool", "cold"]);

function projectFeature(entry: RawRecord): FeatureEntry | null {
    const title = asString(entry.title);
    const description = asString(entry.description);
    return title && description ? { title, description } : null;
}

function projectStep(entry: RawRecord): StepEntry | null {
    const title = asString(entry.title);
    const description = asString(entry.description);
    return title && description ? { title, description } : null;
}

function projectShortcut(entry: RawRecord): ShortcutEntry | null {
    const keys = asString(entry.keys);
    const action = asString(entry.action);
    return keys && action ? { keys, action } : null;
}

function projectFaq(entry: RawRecord): FaqEntry | null {
    const question = asString(entry.question);
    const answer = asString(entry.answer);
    return question && answer ? { question, answer } : null;
}

function projectCard(entry: RawRecord): CardEntry | null {
    const title = asString(entry.title);
    const description = asString(entry.description);
    if (!title || !description) return null;
    const href = asString(entry.href);
    const internalHref = href && href.startsWith("/") ? href : undefined;
    return internalHref ? { title, description, href: internalHref } : { title, description };
}

function projectWarmth(entry: RawRecord): WarmthEntry | null {
    const band = entry.band;
    const label = asString(entry.label);
    const description = asString(entry.description);
    if (typeof band !== "string" || !WARMTH_BANDS.has(band as WarmthBand) || !label || !description) {
        return null;
    }
    return { band: band as WarmthBand, label, description };
}

function projectRow(value: unknown): string[] | null {
    if (!Array.isArray(value) || value.length === 0) return null;
    if (!value.every((cell): cell is string => typeof cell === "string")) return null;
    return value as string[];
}

/**
 * Validate one raw i18n value into a typed {@link DocBlock}, or `null` when the
 * shape is malformed. Keeping validation here means every block reaching a
 * component is well-formed and typed, with no `any` leaking out of the i18n
 * boundary.
 */
function validateBlock(raw: unknown): DocBlock | null {
    if (!isRecord(raw)) return null;
    const type = raw.type;
    if (typeof type !== "string" || !DOC_BLOCK_TYPES.has(type as DocBlock["type"])) return null;

    switch (type as DocBlock["type"]) {
        case "prose": {
            const text = asStringOrStringArray(raw.text);
            return text ? { type: "prose", text } : null;
        }
        case "heading": {
            const text = asString(raw.text);
            if (!text) return null;
            const level = raw.level === 3 ? 3 : 2;
            return { type: "heading", text, level };
        }
        case "featureList": {
            const items = mapEntries(raw.items, projectFeature);
            if (items.length === 0) return null;
            const title = asString(raw.title) ?? undefined;
            const columns = raw.columns === 1 ? 1 : 2;
            return { type: "featureList", title, columns, items };
        }
        case "callout": {
            const variant = raw.variant;
            const body = asStringOrStringArray(raw.body);
            if (
                typeof variant !== "string" ||
                !["note", "tip", "warning", "quirk"].includes(variant) ||
                !body
            ) {
                return null;
            }
            const title = asString(raw.title) ?? undefined;
            return {
                type: "callout",
                variant: variant as "note" | "tip" | "warning" | "quirk",
                title,
                body,
            };
        }
        case "steps": {
            const items = mapEntries(raw.items, projectStep);
            if (items.length === 0) return null;
            return { type: "steps", title: asString(raw.title) ?? undefined, items };
        }
        case "shortcuts": {
            const items = mapEntries(raw.items, projectShortcut);
            if (items.length === 0) return null;
            return { type: "shortcuts", title: asString(raw.title) ?? undefined, items };
        }
        case "faq": {
            const items = mapEntries(raw.items, projectFaq);
            if (items.length === 0) return null;
            return { type: "faq", title: asString(raw.title) ?? undefined, items };
        }
        case "cards": {
            const items = mapEntries(raw.items, projectCard);
            if (items.length === 0) return null;
            return { type: "cards", title: asString(raw.title) ?? undefined, items };
        }
        case "warmthLegend": {
            const items = mapEntries(raw.items, projectWarmth);
            if (items.length === 0) return null;
            return { type: "warmthLegend", title: asString(raw.title) ?? undefined, items };
        }
        case "table": {
            const columns = Array.isArray(raw.columns)
                ? raw.columns.filter((column): column is string => typeof column === "string")
                : [];
            const rows = (Array.isArray(raw.rows)
                ? raw.rows.map(projectRow).filter((row): row is string[] => row !== null)
                : []
            ).filter((row) => row.length === columns.length);
            if (columns.length === 0 || rows.length === 0) return null;
            return { type: "table", title: asString(raw.title) ?? undefined, columns, rows };
        }
        case "illustration": {
            const name = raw.name;
            if (typeof name !== "string" || !(ILLUSTRATION_NAMES as readonly string[]).includes(name)) {
                return null;
            }
            return {
                type: "illustration",
                name: name as IllustrationName,
                caption: asString(raw.caption) ?? undefined,
            };
        }
        default:
            return null;
    }
}

function validateBlocks(raw: unknown): DocBlock[] {
    if (!Array.isArray(raw)) return [];
    return raw.reduce<DocBlock[]>((acc, entry) => {
        const block = validateBlock(entry);
        if (block !== null) acc.push(block);
        return acc;
    }, []);
}

/** Read and validate an article's content blocks from the `docs` namespace. */
export async function readArticleBlocks(
    category: DocCategory,
    article: DocArticle,
): Promise<DocBlock[]> {
    const t = await getTranslations(category.namespace);
    const key = articleBlocksKey(article.slug);
    if (!t.has(key)) return [];
    return validateBlocks(t.raw(key));
}

/** Read and validate the optional intro blocks shown on a category landing. */
export async function readCategoryBlocks(category: DocCategory): Promise<DocBlock[]> {
    const t = await getTranslations(category.namespace);
    if (!t.has("blocks")) return [];
    return validateBlocks(t.raw("blocks"));
}

/** Read and validate the docs home page content blocks. */
export async function readHomeBlocks(): Promise<DocBlock[]> {
    const t = await getTranslations("DocsHome");
    if (!t.has("blocks")) return [];
    return validateBlocks(t.raw("blocks"));
}
