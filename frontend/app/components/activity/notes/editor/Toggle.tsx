"use client";

import { Node, mergeAttributes } from "@tiptap/core";
import { NodeViewContent, NodeViewWrapper, ReactNodeViewRenderer } from "@tiptap/react";
import type { NodeViewProps } from "@tiptap/react";
import type { Node as PMNode } from "@tiptap/pm/model";
import { ChevronRight } from "lucide-react";
import type { MarkdownSerializeState } from "./markdownSerialize";

export interface ToggleOptions {
    expandLabel: string;
    collapseLabel: string;
}

/**
 * The always-visible summary line of a {@link Toggle}. A single rich text line;
 * serializes exactly like a paragraph so it round-trips as the first `> `-quoted
 * line inside the toggle's admonition block.
 */
export const ToggleSummary = Node.create({
    name: "toggleSummary",
    content: "inline*",
    defining: true,
    selectable: false,

    parseHTML() {
        return [{ tag: "div[data-toggle-summary]" }];
    },

    renderHTML({ HTMLAttributes }) {
        return [
            "div",
            mergeAttributes(HTMLAttributes, { "data-toggle-summary": "", class: "note-toggle-summary" }),
            0,
        ];
    },

    addStorage() {
        return {
            markdown: {
                serialize(state: MarkdownSerializeState, node: PMNode) {
                    state.renderInline(node);
                    state.closeBlock(node);
                },
                parse: {},
            },
        };
    },
});

function ToggleView({ node, updateAttributes, extension }: NodeViewProps) {
    const open = node.attrs.open !== false;
    const options = extension.options as ToggleOptions;
    return (
        <NodeViewWrapper
            as="div"
            data-toggle=""
            data-open={open ? "true" : "false"}
            className="note-toggle"
        >
            <button
                type="button"
                contentEditable={false}
                onClick={() => updateAttributes({ open: !open })}
                className="note-toggle-chevron"
                aria-expanded={open}
                aria-label={open ? options.collapseLabel : options.expandLabel}
            >
                <ChevronRight className="size-4" />
            </button>
            <NodeViewContent className="note-toggle-content" />
        </NodeViewWrapper>
    );
}

/**
 * Collapsible toggle — a summary line plus rich block content that expands and
 * collapses. Serializes to a `> [!toggle]` blockquote-admonition (summary is the
 * first quoted line); on load, `parse.updateDOM` re-tags the blockquote and marks
 * its first paragraph as the summary. The open/closed state is a UI-only attr and
 * is not persisted to Markdown (toggles load expanded).
 */
export const Toggle = Node.create<ToggleOptions>({
    name: "toggle",
    group: "block",
    content: "toggleSummary block+",
    defining: true,

    addOptions() {
        return { expandLabel: "Expand", collapseLabel: "Collapse" };
    },

    addAttributes() {
        return {
            open: {
                default: true,
                parseHTML: (element) => element.getAttribute("data-open") !== "false",
                renderHTML: (attributes) => ({ "data-open": attributes.open ? "true" : "false" }),
            },
        };
    },

    parseHTML() {
        return [{ tag: "div[data-toggle]" }];
    },

    renderHTML({ HTMLAttributes }) {
        return ["div", mergeAttributes(HTMLAttributes, { class: "note-toggle" }), 0];
    },

    addNodeView() {
        return ReactNodeViewRenderer(ToggleView);
    },

    addStorage() {
        return {
            markdown: {
                serialize(state: MarkdownSerializeState, node: PMNode) {
                    state.wrapBlock("> ", null, node, () => {
                        state.write("[!toggle]");
                        state.ensureNewLine();
                        state.write("\n");
                        state.renderContent(node);
                    });
                },
                parse: {
                    updateDOM(element: HTMLElement) {
                        element.querySelectorAll("blockquote").forEach((quote) => {
                            const first = quote.firstElementChild;
                            if (first?.textContent?.trim() !== "[!toggle]") return;
                            first.remove();
                            const box = element.ownerDocument.createElement("div");
                            box.setAttribute("data-toggle", "");
                            while (quote.firstChild) box.appendChild(quote.firstChild);
                            const summary = box.firstElementChild;
                            if (summary) {
                                const line = element.ownerDocument.createElement("div");
                                line.setAttribute("data-toggle-summary", "");
                                while (summary.firstChild) line.appendChild(summary.firstChild);
                                summary.replaceWith(line);
                            }
                            quote.replaceWith(box);
                        });
                    },
                },
            },
        };
    },
});
