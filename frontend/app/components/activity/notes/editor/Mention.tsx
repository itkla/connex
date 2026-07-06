"use client";

import { Node, mergeAttributes } from "@tiptap/core";
import type { Editor } from "@tiptap/core";
import { NodeViewWrapper, ReactNodeViewRenderer } from "@tiptap/react";
import type { NodeViewProps } from "@tiptap/react";
import type { Node as PMNode } from "@tiptap/pm/model";
import { PluginKey } from "@tiptap/pm/state";
import Suggestion from "@tiptap/suggestion";
import { createSuggestionRenderer } from "./suggestionRenderer";
import { MentionList } from "./MentionList";
import { queryMentions } from "./mentionData";
import type { MentionItem, MentionTrigger, MentionType } from "./mentionData";

const TOKEN = /\[([^\]]+)\]\((user|person|deal|company|note|file|task|activity):(\d+)\)/g;
const SENTINEL_OPEN = "\uE000";
const SENTINEL_CLOSE = "\uE001";
const SENTINEL = /\uE000(\d+)\uE001/g;

export type MentionAttrs = { refType: MentionType; refId: number; label: string };

export interface MentionOptions {
    excludeUserId?: number;
}

function MentionView({ node }: NodeViewProps) {
    const isUser = node.attrs.refType === "user";
    return (
        <NodeViewWrapper as="span" className="inline">
            <span
                data-ref-type={node.attrs.refType as string}
                data-ref-id={node.attrs.refId as number}
                className={
                    isUser
                        ? "rounded-sm bg-brand-light/50 px-0.5 font-medium text-brand-dark"
                        : "rounded-sm bg-muted px-1 font-medium text-foreground"
                }
            >
                {isUser ? `@${node.attrs.label as string}` : (node.attrs.label as string)}
            </span>
        </NodeViewWrapper>
    );
}

function mentionSuggestion(editor: Editor, char: MentionTrigger, excludeUserId?: number) {
    return Suggestion<MentionItem>({
        editor,
        char,
        pluginKey: new PluginKey(`note-mention-${char}`),
        allowSpaces: false,
        allow: ({ state, range }) => {
            if (char !== "#") return true;
            const resolved = state.doc.resolve(range.from);
            const before = resolved.parent.textBetween(0, resolved.parentOffset, undefined, " ");
            return before.trim().length > 0;
        },
        items: ({ query }) => queryMentions(char, query, excludeUserId),
        command: ({ editor: instance, range, props }) => {
            instance
                .chain()
                .focus()
                .insertContentAt(range, [
                    {
                        type: "mention",
                        attrs: { refType: props.type, refId: props.id, label: props.label },
                    },
                    { type: "text", text: " " },
                ])
                .run();
        },
        render: createSuggestionRenderer(MentionList),
    });
}

/**
 * Inline atom node representing an @/#-reference to a workspace entity. Renders
 * the existing chip styling as a node view and serializes to the canonical
 * `[Label](type:id)` token that the backend ReferenceService parses.
 */
export const Mention = Node.create<MentionOptions>({
    name: "mention",
    group: "inline",
    inline: true,
    atom: true,
    selectable: true,
    draggable: false,

    addOptions() {
        return { excludeUserId: undefined };
    },

    addAttributes() {
        return {
            refType: {
                default: null,
                parseHTML: (element) => element.getAttribute("data-ref-type"),
                renderHTML: (attributes) =>
                    attributes.refType ? { "data-ref-type": attributes.refType as string } : {},
            },
            refId: {
                default: null,
                parseHTML: (element) => {
                    const value = element.getAttribute("data-ref-id");
                    return value ? Number(value) : null;
                },
                renderHTML: (attributes) =>
                    attributes.refId != null ? { "data-ref-id": String(attributes.refId) } : {},
            },
            label: {
                default: "",
                parseHTML: (element) => element.getAttribute("data-label") ?? element.textContent ?? "",
                renderHTML: (attributes) => ({ "data-label": (attributes.label as string) ?? "" }),
            },
        };
    },

    parseHTML() {
        return [{ tag: "span[data-ref-type][data-ref-id]" }];
    },

    renderHTML({ node, HTMLAttributes }) {
        const isUser = node.attrs.refType === "user";
        return [
            "span",
            mergeAttributes(HTMLAttributes),
            `${isUser ? "@" : ""}${node.attrs.label as string}`,
        ];
    },

    renderText({ node }) {
        const isUser = node.attrs.refType === "user";
        return `${isUser ? "@" : ""}${node.attrs.label as string}`;
    },

    addNodeView() {
        return ReactNodeViewRenderer(MentionView);
    },

    addStorage() {
        return {
            markdown: {
                serialize(state: { write: (text: string) => void }, node: PMNode) {
                    const attrs = node.attrs as MentionAttrs;
                    state.write(`[${attrs.label}](${attrs.refType}:${attrs.refId})`);
                },
                parse: {},
            },
        };
    },

    addProseMirrorPlugins() {
        return [
            mentionSuggestion(this.editor, "@", this.options.excludeUserId),
            mentionSuggestion(this.editor, "#", this.options.excludeUserId),
        ];
    },
});

/**
 * Replace `[Label](type:id)` tokens with private-use sentinels so Markdown
 * parsing leaves them intact; the returned mentions are restored as nodes after
 * the document loads (see {@link restoreMentions}).
 */
export function encodeMentions(content: string): { text: string; mentions: MentionAttrs[] } {
    const mentions: MentionAttrs[] = [];
    const text = content.replace(TOKEN, (_full, label: string, type: string, id: string) => {
        const index = mentions.length;
        mentions.push({ refType: type as MentionType, refId: Number(id), label });
        return `${SENTINEL_OPEN}${index}${SENTINEL_CLOSE}`;
    });
    return { text, mentions };
}

/**
 * Convert the sentinels planted by {@link encodeMentions} into mention nodes,
 * once the Markdown document has been loaded into the editor.
 */
export function restoreMentions(editor: Editor, mentions: MentionAttrs[]): void {
    if (!mentions.length) return;
    const mentionType = editor.schema.nodes.mention;
    if (!mentionType) return;
    const { state } = editor;
    const replacements: { from: number; to: number; attrs: MentionAttrs }[] = [];
    state.doc.descendants((node, pos) => {
        if (!node.isText || !node.text) return;
        SENTINEL.lastIndex = 0;
        let match: RegExpExecArray | null = SENTINEL.exec(node.text);
        while (match !== null) {
            const attrs = mentions[Number(match[1])];
            if (attrs) {
                const from = pos + match.index;
                replacements.push({ from, to: from + match[0].length, attrs });
            }
            match = SENTINEL.exec(node.text);
        }
    });
    if (!replacements.length) return;
    let tr = state.tr;
    for (const replacement of replacements.reverse()) {
        tr = tr.replaceWith(replacement.from, replacement.to, mentionType.create(replacement.attrs));
    }
    tr.setMeta("addToHistory", false);
    editor.view.dispatch(tr);
}
