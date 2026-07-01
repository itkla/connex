import { type NoteReference, type NoteReferenceType } from "@/app/lib/types";

/**
 * A parsed run of note content: either literal text or a resolved inline
 * reference. Only references present in the note's server-resolved
 * {@link NoteReference} set become chips; unresolved tokens fall back to plain
 * text so raw `[Label](type:id)` syntax is never shown.
 */
export type NoteSegment =
    | { kind: "text"; value: string }
    | { kind: "reference"; refType: NoteReferenceType; id: number; label: string };

const TOKEN = /\[([^\]]+)\]\((user|person|deal|company):(\d+)\)/g;

/**
 * Splits note content into ordered text and reference segments. A token is
 * emitted as a reference only when its `(type, id)` appears in `references`;
 * otherwise it degrades to `@label` (for members) or `label` text.
 *
 * @param content the raw note content
 * @param references the note's server-resolved references
 * @returns the ordered segments to render
 */
export function parseNoteContent(content: string, references: NoteReference[] = []): NoteSegment[] {
    if (!content) return [];
    const resolved = new Map(references.map((reference) => [`${reference.type}:${reference.id}`, reference]));
    const segments: NoteSegment[] = [];
    let lastIndex = 0;

    for (const match of content.matchAll(TOKEN)) {
        const [full, label, rawType, rawId] = match;
        const start = match.index ?? 0;
        if (start > lastIndex) {
            segments.push({ kind: "text", value: content.slice(lastIndex, start) });
        }
        const refType = rawType as NoteReferenceType;
        const id = Number(rawId);
        const reference = resolved.get(`${refType}:${id}`);
        if (reference) {
            segments.push({ kind: "reference", refType, id, label: reference.label });
        } else {
            segments.push({ kind: "text", value: refType === "user" ? `@${label}` : label });
        }
        lastIndex = start + full.length;
    }

    if (lastIndex < content.length) {
        segments.push({ kind: "text", value: content.slice(lastIndex) });
    }
    return segments;
}

/**
 * Flattens note content to plain text for previews and labels, replacing each
 * `[Label](type:id)` reference token with `@Label`.
 *
 * @param content the raw note content
 * @returns the content with tokens reduced to their labels
 */
export function noteContentToPlainText(content: string): string {
    return content.replace(/\[([^\]]+)\]\((?:user|person|deal|company):\d+\)/g, (_full, label: string) => `@${label}`);
}
