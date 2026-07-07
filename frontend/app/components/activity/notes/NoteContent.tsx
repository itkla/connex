"use client";

import { Children, Fragment, isValidElement, type ReactNode } from "react";
import Markdown, { defaultUrlTransform, type Components } from "react-markdown";
import rehypeSanitize, { defaultSchema, type Options as SanitizeOptions } from "rehype-sanitize";
import remarkGfm from "remark-gfm";

import { type NoteReference } from "@/app/lib/types";

import MentionChip from "./MentionChip";
import RecordChip from "./RecordChip";

const REFERENCE_TYPES = ["user", "person", "deal", "company", "note", "file", "task", "activity"] as const;
const MARKDOWN_PLUGINS = [remarkGfm];
const SANITIZE_SCHEMA: SanitizeOptions = {
    ...defaultSchema,
    tagNames: defaultSchema.tagNames?.filter((tagName) => tagName !== "img"),
    attributes: {
        ...defaultSchema.attributes,
        input: [...(defaultSchema.attributes?.input ?? []), ["checked", true]],
    },
    protocols: {
        ...defaultSchema.protocols,
        href: [...(defaultSchema.protocols?.href ?? []), ...REFERENCE_TYPES],
    },
};

type ParsedReferenceHref = { type: NoteReference["type"]; id: number };

function toReferenceType(value: string): NoteReference["type"] | null {
    switch (value) {
        case "user":
        case "person":
        case "deal":
        case "company":
        case "note":
        case "file":
        case "task":
        case "activity":
            return value;
        default:
            return null;
    }
}

function parseReferenceHref(href: string | undefined): ParsedReferenceHref | null {
    if (!href) return null;
    const separator = href.indexOf(":");
    if (separator < 1) return null;
    const type = toReferenceType(href.slice(0, separator));
    const id = Number(href.slice(separator + 1));
    if (!type || !Number.isInteger(id) || id <= 0) return null;
    return { type, id };
}

function textFromChildren(children: ReactNode): string {
    return Children.toArray(children)
        .map((child) => {
            if (typeof child === "string" || typeof child === "number") return String(child);
            if (isValidElement<{ children?: ReactNode }>(child)) return textFromChildren(child.props.children);
            return "";
        })
        .join("")
        .trim();
}

function markdownUrlTransform(url: string): string {
    return parseReferenceHref(url) ? url : defaultUrlTransform(url);
}

function createMarkdownComponents(references: NoteReference[], inline: boolean): Components {
    const resolved = new Map(references.map((reference) => [`${reference.type}:${reference.id}`, reference]));
    const components: Components = {
        a({ href, children }) {
            const parsed = parseReferenceHref(href);
            if (parsed) {
                const reference = resolved.get(`${parsed.type}:${parsed.id}`);
                if (!reference) {
                    const label = textFromChildren(children);
                    return <>{parsed.type === "user" ? `@${label}` : label}</>;
                }
                if (parsed.type === "user") {
                    return <MentionChip id={parsed.id} label={reference.label} />;
                }
                return <RecordChip type={parsed.type} id={parsed.id} label={reference.label} />;
            }
            return (
                <a
                    href={href}
                    referrerPolicy="no-referrer"
                    rel="noreferrer"
                    onClick={(event) => event.stopPropagation()}
                >
                    {children}
                </a>
            );
        },
        input({ checked }) {
            return (
                <input
                    type="checkbox"
                    checked={Boolean(checked)}
                    readOnly
                    disabled
                    className="mt-1 size-3.5 rounded border-border accent-brand"
                />
            );
        },
    };

    if (!inline) return components;

    return {
        ...components,
        p({ children }) {
            return <>{children}</>;
        },
        h1({ children }) {
            return <strong>{children}</strong>;
        },
        h2({ children }) {
            return <strong>{children}</strong>;
        },
        h3({ children }) {
            return <strong>{children}</strong>;
        },
        h4({ children }) {
            return <strong>{children}</strong>;
        },
        h5({ children }) {
            return <strong>{children}</strong>;
        },
        h6({ children }) {
            return <strong>{children}</strong>;
        },
        ul({ children }) {
            return <span>{children}</span>;
        },
        ol({ children }) {
            return <span>{children}</span>;
        },
        li({ children }) {
            return <span>{children}</span>;
        },
        blockquote({ children }) {
            return <span>{children}</span>;
        },
        hr() {
            return <span />;
        },
        pre({ children }) {
            return <code>{children}</code>;
        },
        section({ children }) {
            return <span>{children}</span>;
        },
        table({ children }) {
            return <span>{children}</span>;
        },
        thead({ children }) {
            return <span>{children}</span>;
        },
        tbody({ children }) {
            return <span>{children}</span>;
        },
        tr({ children }) {
            return <span>{children}</span>;
        },
        th({ children }) {
            return <span>{children}</span>;
        },
        td({ children }) {
            return <span>{children}</span>;
        },
    };
}

/**
 * Renders note content as sanitized Markdown, turning Connex reference links
 * into chips while preserving normal Markdown formatting around them.
 */
export default function NoteContent({
    content,
    references,
    className,
    block = false,
}: {
    content: string;
    references?: NoteReference[];
    className?: string;
    block?: boolean;
}) {
    const markdown = (
        <Markdown
            remarkPlugins={MARKDOWN_PLUGINS}
            rehypePlugins={[[rehypeSanitize, SANITIZE_SCHEMA]]}
            urlTransform={markdownUrlTransform}
            components={createMarkdownComponents(references ?? [], !block)}
        >
            {content}
        </Markdown>
    );

    if (block) {
        return <div className={className ? `note-prose ${className}` : "note-prose"}>{markdown}</div>;
    }

    return (
        <span className={className}>
            <Fragment>{markdown}</Fragment>
        </span>
    );
}
