import type { Node as PMNode } from "@tiptap/pm/model";

/**
 * Minimal structural view of prosemirror-markdown's serializer state, exposing
 * only the methods the note block nodes call. Mirrors the inline-typed approach
 * in {@link Mention} to stay strict-typed without importing the full class.
 */
export type MarkdownSerializeState = {
    write: (content: string) => void;
    text: (text: string, escape?: boolean) => void;
    ensureNewLine: () => void;
    closeBlock: (node: PMNode) => void;
    wrapBlock: (delim: string, firstDelim: string | null, node: PMNode, fn: () => void) => void;
    renderContent: (node: PMNode) => void;
    renderInline: (node: PMNode) => void;
};
