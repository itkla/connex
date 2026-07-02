import type { DocBlock } from "./types";

/** A heading extracted from an article, used for anchors and "on this page". */
export type DocHeading = {
    id: string;
    text: string;
    level: 2 | 3;
};

/**
 * Deterministic anchor id for a heading. Slugifies the text where possible and
 * falls back to a positional id, so non-latin (e.g. Japanese) headings still get
 * stable, collision-free anchors. Shared by the server reader and the client
 * renderer so their ids always match.
 */
export function headingId(text: string, index: number): string {
    const slug = text
        .toLowerCase()
        .replace(/[^\p{L}\p{N}]+/gu, "-")
        .replace(/^-+|-+$/g, "");
    const asciiSlug = slug.replace(/[^a-z0-9-]/g, "");
    return asciiSlug.length > 0 ? `${asciiSlug}-${index}` : `section-${index}`;
}

/** Extract the ordered heading list from an article's content blocks. */
export function extractHeadings(blocks: DocBlock[]): DocHeading[] {
    return blocks
        .map((block, index) => ({ block, index }))
        .filter(
            (entry): entry is { block: Extract<DocBlock, { type: "heading" }>; index: number } =>
                entry.block.type === "heading",
        )
        .map(({ block, index }) => ({
            id: headingId(block.text, index),
            text: block.text,
            level: block.level ?? 2,
        }));
}
