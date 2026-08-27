'use client';

import { Children, isValidElement, type ReactNode } from 'react';
import {
    CheckCircleIcon,
    ExclamationCircleIcon,
    ExclamationTriangleIcon,
    InformationCircleIcon,
} from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';
import Markdown, { type Components } from 'react-markdown';
import rehypeSanitize, { defaultSchema, type Options as SanitizeOptions } from 'rehype-sanitize';
import remarkGfm from 'remark-gfm';

import RecordChip from '@/app/components/activity/notes/RecordChip';
import { cn } from '@/lib/utils';

const REFERENCE_TYPES = ['person', 'company', 'deal'] as const;
const MARKDOWN_PLUGINS = [remarkGfm];
const SANITIZE_SCHEMA: SanitizeOptions = {
    ...defaultSchema,
    attributes: {
        ...defaultSchema.attributes,
        input: [...(defaultSchema.attributes?.input ?? []), ['checked', true]],
        li: [...(defaultSchema.attributes?.li ?? []), ['className', 'task-list-item']],
        ul: [...(defaultSchema.attributes?.ul ?? []), ['className', 'contains-task-list']],
    },
    protocols: {
        ...defaultSchema.protocols,
        href: [...(defaultSchema.protocols?.href ?? []), ...REFERENCE_TYPES],
    },
};

type ReferenceType = (typeof REFERENCE_TYPES)[number];
type ParsedReferenceHref = { type: ReferenceType; id: number };
type CalloutVariant = 'info' | 'success' | 'warn' | 'danger';
type Callout = { variant: CalloutVariant; body: ReactNode[] };
type MarkdownLabels = {
    checkbox: { checked: string; unchecked: string };
    callout: Record<CalloutVariant, string>;
};

const CALLOUT_ICONS: Record<CalloutVariant, typeof InformationCircleIcon> = {
    info: InformationCircleIcon,
    success: CheckCircleIcon,
    warn: ExclamationTriangleIcon,
    danger: ExclamationCircleIcon,
};

function toReferenceType(value: string): ReferenceType | null {
    switch (value) {
        case 'person':
        case 'company':
        case 'deal':
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
            if (typeof child === 'string' || typeof child === 'number') return String(child);
            if (isValidElement<{ children?: ReactNode }>(child)) return textFromChildren(child.props.children);
            return '';
        })
        .join('')
        .trim();
}

function meaningfulChildren(children: ReactNode): ReactNode[] {
    return Children.toArray(children).filter(
        (child) => typeof child !== 'string' || child.trim().length > 0,
    );
}

function toCalloutVariant(value: string | undefined): CalloutVariant | null {
    switch (value) {
        case 'info':
        case 'success':
        case 'warn':
        case 'danger':
            return value;
        default:
            return null;
    }
}

/**
 * Reads a `[!variant]` marker from the first line of a blockquote's first paragraph.
 *
 * The marker and the callout's first sentence usually share one paragraph — `> [!info]` followed
 * directly by `> Details` parses as a single `<p>` whose text starts with the marker line — so the
 * marker is read from the paragraph's leading text up to its first newline, and whatever follows
 * stays in the body as its own paragraph alongside any later blocks.
 */
function parseCallout(children: ReactNode): Callout | null {
    const items = meaningfulChildren(children);
    const first = items[0];
    if (!isValidElement<{ children?: ReactNode }>(first)) return null;
    const paragraph = Children.toArray(first.props.children);
    const lead = paragraph[0];
    if (typeof lead !== 'string') return null;
    const newline = lead.indexOf('\n');
    const marker = (newline === -1 ? lead : lead.slice(0, newline)).trim();
    const variant = toCalloutVariant(marker.match(/^\[!(\w+)]$/)?.[1]);
    if (!variant) return null;
    const remainderLead = newline === -1 ? '' : lead.slice(newline + 1);
    const remainder = [
        ...(remainderLead.length > 0 ? [remainderLead] : []),
        ...paragraph.slice(1),
    ];
    const body = remainder.length > 0
        ? [<p key="callout-lead">{remainder}</p>, ...items.slice(1)]
        : items.slice(1);
    return { variant, body };
}

function markdownUrlTransform(url: string, key: string): string {
    if (key === 'src') return '';
    return parseReferenceHref(url) ? url : '';
}

function createMarkdownComponents(
    labels: MarkdownLabels,
    allowedRecords: ReadonlySet<string>,
): Components {
    return {
        a({ href, children }) {
            const parsed = parseReferenceHref(href);
            if (parsed && allowedRecords.has(`${parsed.type}:${parsed.id}`)) {
                const label = textFromChildren(children);
                if (!label) return <>{children}</>;
                return <RecordChip type={parsed.type} id={parsed.id} label={label} />;
            }
            return <>{children}</>;
        },
        img({ alt }) {
            return alt?.trim() || null;
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
            const callout = parseCallout(children);
            if (!callout) return <blockquote>{children}</blockquote>;
            const Icon = CALLOUT_ICONS[callout.variant];
            return (
                <div data-callout={callout.variant} className="note-callout">
                    <span className="note-callout-symbol" aria-hidden="true">
                        <Icon className="size-4" />
                    </span>
                    <div className="note-callout-body">
                        <span className="sr-only">{labels.callout[callout.variant]}: </span>
                        {callout.body}
                    </div>
                </div>
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
}

/**
 * Renders one assistant answer as sanitized Markdown, sized for the chat transcript.
 *
 * Record references written as `person:`/`company:`/`deal:` links become the same record chips
 * note content uses, mirroring the citation routes — but only when `allowedRecords` names them.
 * The content is model output, so a reference the server did not authorize as a citation proves
 * nothing about the viewer's access and renders as plain text instead of a live chip. Every other
 * link renders as its visible text with no anchor at all: tenant URLs are redacted from prompts, so
 * a URL in an answer is unverifiable model output and a live anchor would be a phishing surface
 * inside a trusted UI. Images never render — assistant answers do not embed them — and raw HTML is
 * dropped by the sanitizer.
 */
export default function AskConnexMarkdown({
    content,
    allowedRecords,
    className,
}: {
    content: string;
    /** The viewer-authorized citations, keyed `kind:id`, that may render as live record chips. */
    allowedRecords: ReadonlySet<string>;
    className?: string;
}) {
    const t = useTranslations('ActivityNotesEditor');
    return (
        <div
            className={cn(
                'note-prose text-sm leading-relaxed [&>:first-child]:mt-0 [&>:last-child]:mb-0',
                className,
            )}
        >
            <Markdown
                remarkPlugins={MARKDOWN_PLUGINS}
                rehypePlugins={[[rehypeSanitize, SANITIZE_SCHEMA]]}
                urlTransform={markdownUrlTransform}
                components={createMarkdownComponents({
                    checkbox: {
                        checked: t('taskChecked'),
                        unchecked: t('taskUnchecked'),
                    },
                    callout: {
                        info: t('calloutInfo'),
                        success: t('calloutSuccess'),
                        warn: t('calloutWarning'),
                        danger: t('calloutDanger'),
                    },
                }, allowedRecords)}
                skipHtml
            >
                {content}
            </Markdown>
        </div>
    );
}
