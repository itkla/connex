"use client";

import { Children, Fragment, isValidElement, type ReactNode } from "react";
import { CircleCheck, Info, OctagonAlert, TriangleAlert, type LucideIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import Markdown, { type Components } from "react-markdown";
import rehypeSanitize, { defaultSchema, type Options as SanitizeOptions } from "rehype-sanitize";
import remarkGfm from "remark-gfm";

import { type NoteReference } from "@/app/lib/types";
import { normalizeEditorLinkHref } from "./editor/editorLinks";
import { normalizeNoteImageSource } from "./editor/noteImageSource";
import MentionChip from "./MentionChip";
import NoteMarkdownImage from "./NoteMarkdownImage";
import RecordChip from "./RecordChip";

const REFERENCE_TYPES = ["user", "person", "deal", "company", "note", "file", "task", "activity"] as const;
const MARKDOWN_PLUGINS = [remarkGfm];
const SANITIZE_SCHEMA: SanitizeOptions = {
    ...defaultSchema,
    attributes: {
        ...defaultSchema.attributes,
        input: [...(defaultSchema.attributes?.input ?? []), ["checked", true]],
        li: [...(defaultSchema.attributes?.li ?? []), ["className", "task-list-item"]],
        ul: [...(defaultSchema.attributes?.ul ?? []), ["className", "contains-task-list"]],
    },
    protocols: {
        ...defaultSchema.protocols,
        href: [...(defaultSchema.protocols?.href ?? []), ...REFERENCE_TYPES],
    },
};

type ParsedReferenceHref = { type: NoteReference["type"]; id: number };
type CalloutVariant = "info" | "success" | "warn" | "danger";
type Admonition =
    | { kind: "callout"; variant: CalloutVariant; body: ReactNode[] }
    | { kind: "toggle"; summary: ReactNode; body: ReactNode[] };
type MarkdownLabels = {
    checkbox: { checked: string; unchecked: string };
    callout: Record<CalloutVariant, string>;
    unavailableReference: string;
};

const CALLOUT_ICONS: Record<CalloutVariant, LucideIcon> = {
    info: Info,
    success: CircleCheck,
    warn: TriangleAlert,
    danger: OctagonAlert,
};

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
    const match = href.match(/^([a-z]+):([1-9]\d*)$/);
    if (!match) return null;
    const type = toReferenceType(match[1]);
    const id = Number(match[2]);
    if (!type || !Number.isSafeInteger(id) || id <= 0) return null;
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

function meaningfulChildren(children: ReactNode): ReactNode[] {
    return Children.toArray(children).filter(
        (child) => typeof child !== "string" || child.trim().length > 0,
    );
}

function unwrapChildren(node: ReactNode): ReactNode {
    return isValidElement<{ children?: ReactNode }>(node) ? node.props.children : node;
}

function toCalloutVariant(value: string | undefined): CalloutVariant | null {
    switch (value) {
        case "info":
        case "success":
        case "warn":
        case "danger":
            return value;
        default:
            return null;
    }
}

function parseAdmonition(children: ReactNode): Admonition | null {
    const items = meaningfulChildren(children);
    const marker = textFromChildren(items[0]);
    const callout = marker.match(/^\[!(\w+)]$/);
    const variant = toCalloutVariant(callout?.[1]);
    if (variant) {
        return {
            kind: "callout",
            variant,
            body: items.slice(1),
        };
    }
    if (marker !== "[!toggle]" || items.length < 2) return null;
    return { kind: "toggle", summary: unwrapChildren(items[1]), body: items.slice(2) };
}

function markdownUrlTransform(url: string, key: string): string {
    if (key === "src") return normalizeNoteImageSource(url) ?? "";
    return parseReferenceHref(url) ? url : normalizeEditorLinkHref(url) ?? "";
}

function createMarkdownComponents(
    references: NoteReference[],
    inline: boolean,
    labels: MarkdownLabels,
): Components {
    const resolved = new Map(references.map((reference) => [`${reference.type}:${reference.id}`, reference]));
    const components: Components = {
        a({ href, children }) {
            const parsed = parseReferenceHref(href);
            if (parsed) {
                const reference = resolved.get(`${parsed.type}:${parsed.id}`);
                if (!reference) {
                    return <>{labels.unavailableReference}</>;
                }
                if (parsed.type === "user") {
                    return <MentionChip id={parsed.id} label={reference.label} />;
                }
                return <RecordChip type={parsed.type} id={parsed.id} label={reference.label} />;
            }
            if (!href) return <>{children}</>;
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
        img({ src, alt }) {
            const source = typeof src === "string" ? normalizeNoteImageSource(src) : null;
            const description = alt?.trim();
            if (!source || !description) return description ?? null;
            return inline ? description : <NoteMarkdownImage source={source} alt={description} />;
        },
        input({ checked }) {
            return (
                <input
                    type="checkbox"
                    checked={Boolean(checked)}
                    readOnly
                    disabled
                    aria-label={checked ? labels.checkbox.checked : labels.checkbox.unchecked}
                    className="mt-1 size-3.5 rounded border-border accent-brand"
                />
            );
        },
        blockquote({ children }) {
            const admonition = parseAdmonition(children);
            if (!admonition) return <blockquote>{children}</blockquote>;
            if (admonition.kind === "callout") {
                const Icon = CALLOUT_ICONS[admonition.variant];
                return (
                    <div data-callout={admonition.variant} className="note-callout">
                        <span className="note-callout-symbol" aria-hidden="true">
                            <Icon className="size-4" />
                        </span>
                        <div className="note-callout-body">
                            <span className="sr-only">{labels.callout[admonition.variant]}: </span>
                            {admonition.body}
                        </div>
                    </div>
                );
            }
            return (
                <details className="note-toggle" open>
                    <summary className="note-toggle-summary">{admonition.summary}</summary>
                    <div className="note-toggle-content">{admonition.body}</div>
                </details>
            );
        },
        table({ children }) {
            return (
                <div className="tableWrapper">
                    <table>{children}</table>
                </div>
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
            const admonition = parseAdmonition(children);
            if (!admonition) return <span>{children}</span>;
            if (admonition.kind === "callout") return <span>{admonition.body}</span>;
            return (
                <span>
                    <strong>{admonition.summary}</strong> {admonition.body}
                </span>
            );
        },
        hr() {
            return <span />;
        },
        pre({ children }) {
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

/** Renders sanitized note Markdown while resolving only server-authorized references into chips. */
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
    const t = useTranslations("ActivityNotesEditor");
    const markdown = (
        <Markdown
            remarkPlugins={MARKDOWN_PLUGINS}
            rehypePlugins={[[rehypeSanitize, SANITIZE_SCHEMA]]}
            urlTransform={markdownUrlTransform}
            components={createMarkdownComponents(references ?? [], !block, {
                checkbox: {
                    checked: t("taskChecked"),
                    unchecked: t("taskUnchecked"),
                },
                callout: {
                    info: t("calloutInfo"),
                    success: t("calloutSuccess"),
                    warn: t("calloutWarning"),
                    danger: t("calloutDanger"),
                },
                unavailableReference: t("unavailableReference"),
            })}
            skipHtml
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
