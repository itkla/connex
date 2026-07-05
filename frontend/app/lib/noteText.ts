import type { Note } from "@/app/lib/types";

const TOKEN = /\[([^\]]+)\]\((user|person|deal|company|note|file):(\d+)\)/g;

function collapseTokens(value: string): string {
    return value.replace(TOKEN, (_full, label: string, type: string) =>
        type === "user" ? `@${label}` : label,
    );
}

/**
 * Collapse stored note content (Markdown plus inline reference tokens) into a
 * single-line plain-text snippet for list rows and previews.
 */
export function noteSnippet(content: string, max = 180): string {
    const plain = collapseTokens(content)
        .replace(/```[\s\S]*?```/g, " ")
        .replace(/`([^`]*)`/g, "$1")
        .replace(/^\s{0,3}#{1,6}\s+/gm, "")
        .replace(/^\s*[-*+]\s+(\[[ xX]\]\s+)?/gm, "")
        .replace(/^\s*\d+\.\s+/gm, "")
        .replace(/^\s*>\s?/gm, "")
        .replace(/!?\[([^\]]*)\]\([^)]*\)/g, "$1")
        .replace(/[*_~]{1,3}([^*_~]+)[*_~]{1,3}/g, "$1")
        .replace(/\s+/g, " ")
        .trim();
    return plain.length > max ? `${plain.slice(0, max).trimEnd()}…` : plain;
}

/**
 * Resolve a note's display title: the explicit title when present, otherwise
 * the first non-empty line of the body.
 */
export function deriveNoteTitle(note: Pick<Note, "title" | "content">, fallback = ""): string {
    const explicit = note.title?.trim();
    if (explicit) return explicit;
    const firstLine = note.content
        .split("\n")
        .map((line) => line.replace(/^\s{0,3}#{1,6}\s+/, "").trim())
        .find((line) => line.length > 0);
    if (!firstLine) return fallback;
    const plain = collapseTokens(firstLine);
    return plain.length > 120 ? `${plain.slice(0, 120).trimEnd()}…` : plain;
}
