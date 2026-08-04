"use client";

import { useState } from "react";
import { InputRule } from "@tiptap/core";
import { Image, inputRegex, type ImageOptions, type SetImageOptions } from "@tiptap/extension-image";
import type { NodeType } from "@tiptap/pm/model";
import { NodeViewWrapper, ReactNodeViewRenderer, type NodeViewProps } from "@tiptap/react";
import { ImageOff } from "lucide-react";

import { cn } from "@/lib/utils";
import { normalizeNoteImageSource } from "./noteImageSource";

export { normalizeNoteImageSource } from "./noteImageSource";

export type MarkdownImageOptions = ImageOptions & {
    loadError: string;
};

function imageAttribute(value: unknown): string {
    return typeof value === "string" ? value : "";
}

function normalizeImageAlt(value: unknown): string | null {
    const alt = imageAttribute(value).trim();
    return alt || null;
}

type MarkdownImageAttributes = {
    src: string;
    alt: string;
    title?: string;
};

/** Returns validated image attributes from a matched Markdown image input rule. */
export function markdownImageInputAttributes(match: RegExpMatchArray): MarkdownImageAttributes | null {
    const [, , rawAlt, rawSource, title] = match;
    const src = normalizeNoteImageSource(rawSource ?? "");
    const alt = normalizeImageAlt(rawAlt);
    return src && alt ? { src, alt, title } : null;
}

/** Creates the validated Markdown image input rule used by note editors. */
export function createMarkdownImageInputRule(type: NodeType): InputRule {
    return new InputRule({
        find: inputRegex,
        handler: ({ state, range, match }) => {
            const attributes = markdownImageInputAttributes(match);
            const matchedMarkdown = match[1];
            const lastCharacter = match[0].at(-1);
            if (!attributes || !matchedMarkdown || !lastCharacter) return null;

            const { tr } = state;
            const start = range.from;
            let end = range.to;
            let matchStart = start + match[0].lastIndexOf(matchedMarkdown);

            if (matchStart > end) {
                matchStart = end;
            } else {
                end = matchStart + matchedMarkdown.length;
            }

            tr.insertText(lastCharacter, start + match[0].length - 1);
            tr.replaceWith(matchStart, end, type.create(attributes));
            tr.scrollIntoView();
        },
    });
}

function MarkdownImageView({ extension, node, selected }: NodeViewProps) {
    const source = normalizeNoteImageSource(imageAttribute(node.attrs.src));
    const alt = normalizeImageAlt(node.attrs.alt);
    const loadError = imageAttribute(extension.options.loadError);
    const [failedSource, setFailedSource] = useState<string | null>(null);
    const available = source !== null && failedSource !== source;

    return (
        <NodeViewWrapper
            as="figure"
            data-drag-handle
            className={cn("note-image-frame", selected && "ProseMirror-selectednode")}
            contentEditable={false}
        >
            {available ? (
                <img
                    src={source}
                    alt={alt ?? ""}
                    loading="lazy"
                    decoding="async"
                    draggable={false}
                    onError={() => setFailedSource(source)}
                />
            ) : (
                <div className="note-image-fallback" role="img" aria-label={alt ?? loadError}>
                    <ImageOff className="size-5" aria-hidden="true" />
                    <span>{alt ?? loadError}</span>
                </div>
            )}
            {alt ? <figcaption>{alt}</figcaption> : null}
        </NodeViewWrapper>
    );
}

const MarkdownImage = Image.extend<MarkdownImageOptions>({
    addOptions() {
        const parent = this.parent?.();
        return {
            inline: parent?.inline ?? false,
            allowBase64: false,
            HTMLAttributes: parent?.HTMLAttributes ?? {},
            resize: false,
            loadError: "",
        };
    },

    parseHTML() {
        return [{
            tag: "img[src]:not([src^=\"data:\"])",
            getAttrs: (element) => {
                const src = normalizeNoteImageSource(element.getAttribute("src") ?? "");
                const alt = normalizeImageAlt(element.getAttribute("alt"));
                if (!src || !alt) return false;
                return {
                    src,
                    alt,
                    title: element.getAttribute("title"),
                };
            },
        }];
    },

    addNodeView() {
        return ReactNodeViewRenderer(MarkdownImageView);
    },

    addCommands() {
        return {
            setImage:
                (options: SetImageOptions) =>
                ({ commands }) => {
                    const src = normalizeNoteImageSource(options.src);
                    const alt = normalizeImageAlt(options.alt);
                    if (!src || !alt) return false;
                    return commands.insertContent({
                        type: this.name,
                        attrs: { ...options, src, alt },
                    });
                },
        };
    },

    addInputRules() {
        return [createMarkdownImageInputRule(this.type)];
    },
});

/** Creates the safe Markdown image extension used by note editors. */
export function createMarkdownImageExtension(loadError: string) {
    return MarkdownImage.configure({ loadError });
}
