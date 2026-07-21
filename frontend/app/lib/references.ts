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

const TOKEN = /\[([^\]]+)\]\((user|person|deal|company|note|file|task|activity):(\d+)\)/g;

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
    return content.replace(/\[([^\]]+)\]\((?:user|person|deal|company|note|file|task|activity):\d+\)/g, (_full, label: string) => `@${label}`);
}

/**
 * Reduces note Markdown to the text users see, for draft labels and meaningful-content checks.
 * Canonical member references retain their `@` cue while record references use their visible label.
 */
export function noteContentToVisibleText(content: string): string {
    return content
        .replace(
            /\[([^\]]+)\]\((user|person|deal|company|note|file|task|activity):\d+\)/g,
            (_full, label: string, type: NoteReferenceType) => (type === "user" ? `@${label}` : label),
        )
        .replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1")
        .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
        .split("\n")
        .map((line) => {
            if (/^\s*(?:`{3,}|~{3,})/.test(line)) return "";
            if (/^\s*(?:[-*_]\s*){3,}$/.test(line)) return "";
            if (/^\s*>\s*\[!(?:info|success|warn|danger|toggle)\]\s*$/.test(line)) return "";
            return line
                .replace(/^\s{0,3}(?:#{1,6}(?:\s+|$)|>\s*)/, "")
                .replace(/^\s*(?:[-*+]\s+(?:\[[ xX]\]\s*)?|\d+[.)]\s+)/, "")
                .replace(/\*\*|__|~~|`/g, "")
                .replace(/(^|\s)[*_](?=\S)/g, "$1")
                .replace(/[*_](?=\s|$|[.,!?;:])/g, "");
        })
        .join("\n")
        .trim();
}
