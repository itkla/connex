"use client";

import { Node, mergeAttributes } from "@tiptap/core";
import { NodeViewContent, NodeViewWrapper, ReactNodeViewRenderer } from "@tiptap/react";
import type { NodeViewProps } from "@tiptap/react";
import type { Node as PMNode } from "@tiptap/pm/model";
import { CircleAlert, Info, OctagonAlert, TriangleAlert, type LucideIcon } from "lucide-react";
import type { MarkdownSerializeState } from "./markdownSerialize";

export const CALLOUT_VARIANTS = ["info", "success", "warn", "danger"] as const;
export type CalloutVariant = (typeof CALLOUT_VARIANTS)[number];

const VARIANT_ICON: Record<CalloutVariant, LucideIcon> = {
    info: Info,
    success: CircleAlert,
    warn: TriangleAlert,
    danger: OctagonAlert,
};

function normalizeVariant(value: unknown): CalloutVariant {
    return (CALLOUT_VARIANTS as readonly string[]).includes(value as string)
        ? (value as CalloutVariant)
        : "info";
}

export interface CalloutOptions {
    cycleLabel: string;
}

function CalloutView({ node, updateAttributes, editor, extension }: NodeViewProps) {
    const variant = normalizeVariant(node.attrs.variant);
    const Icon = VARIANT_ICON[variant];
    const cycleLabel = (extension.options as CalloutOptions).cycleLabel;
    const cycle = () => {
        const next =
            CALLOUT_VARIANTS[(CALLOUT_VARIANTS.indexOf(variant) + 1) % CALLOUT_VARIANTS.length];
        updateAttributes({ variant: next });
    };
    return (
        <NodeViewWrapper as="div" data-callout={variant} className="note-callout">
            <button
                type="button"
                contentEditable={false}
                onClick={cycle}
                disabled={!editor.isEditable}
                className="note-callout-icon"
                aria-label={cycleLabel}
            >
                <Icon className="size-4" />
            </button>
            <NodeViewContent className="note-callout-body" />
        </NodeViewWrapper>
    );
}

/**
 * Block callout — a highlighted aside with a variant (info/success/warn/danger).
 * Serializes to a GitHub/Obsidian-style `> [!variant]` blockquote-admonition so
 * it degrades gracefully in any Markdown reader, and re-hydrates on load via the
 * tiptap-markdown `parse.updateDOM` hook (re-tagging the blockquote to a div the
 * node claims). Content can hold any block, including @/# mention tokens.
 */
export const Callout = Node.create<CalloutOptions>({
    name: "callout",
    group: "block",
    content: "block+",
    defining: true,

    addOptions() {
        return { cycleLabel: "Change callout style" };
    },

    addAttributes() {
        return {
            variant: {
                default: "info",
                parseHTML: (element) => normalizeVariant(element.getAttribute("data-callout")),
                renderHTML: (attributes) => ({ "data-callout": normalizeVariant(attributes.variant) }),
            },
        };
    },

    parseHTML() {
        return [{ tag: "div[data-callout]" }];
    },

    renderHTML({ HTMLAttributes }) {
        return ["div", mergeAttributes(HTMLAttributes, { class: "note-callout" }), 0];
    },

    addNodeView() {
        return ReactNodeViewRenderer(CalloutView);
    },

    addStorage() {
        return {
            markdown: {
                serialize(state: MarkdownSerializeState, node: PMNode) {
                    const variant = normalizeVariant(node.attrs.variant);
                    state.wrapBlock("> ", null, node, () => {
                        state.write(`[!${variant}]`);
                        state.ensureNewLine();
                        state.write("\n");
                        state.renderContent(node);
                    });
                },
                parse: {
                    updateDOM(element: HTMLElement) {
                        element.querySelectorAll("blockquote").forEach((quote) => {
                            const first = quote.firstElementChild;
                            const marker = first?.textContent?.trim().match(/^\[!(\w+)\]$/);
                            if (!marker) return;
                            const variant = marker[1];
                            if (!(CALLOUT_VARIANTS as readonly string[]).includes(variant)) return;
                            first?.remove();
                            const box = element.ownerDocument.createElement("div");
                            box.setAttribute("data-callout", variant);
                            while (quote.firstChild) box.appendChild(quote.firstChild);
                            quote.replaceWith(box);
                        });
                    },
                },
            },
        };
    },
});
