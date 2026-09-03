'use client';

import { Fragment, type CSSProperties, type ReactNode } from 'react';
import { useLocale, useTranslations } from 'next-intl';

import { formatCurrency, formatUtcDateTime } from '@/app/lib/utils';
import type {
    DocumentBodyMark,
    DocumentBodyNode,
    DocumentContent,
    DocumentParty,
    DocumentStatus,
    DocumentType,
} from '@/app/lib/types';

const TYPE_KEY: Record<DocumentType, string> = {
    quote: 'typeQuote',
    proposal: 'typeProposal',
    order_form: 'typeOrderForm',
    contract: 'typeContract',
};

type Props = {
    content: DocumentContent;
    type: DocumentType;
    title?: string;
    status?: DocumentStatus;
    version?: number;
    generatedAt?: string;
};

/**
 * Canonical rendering of a commercial document's resolved content. Renders the block body when the
 * template used the block builder, expanding the {@code lineItems} placeholder into the frozen table;
 * otherwise it falls back to the legacy intro/terms/footer sections. Presentational only — it renders
 * the paper body, not page or print chrome, and never computes money (totals come pre-computed).
 */
export default function DocumentView({ content, type, title, status, version, generatedAt }: Props) {
    const t = useTranslations('DealsDocuments');
    const tp = useTranslations('DealsDocuments.print');
    const locale = useLocale();
    const money = (value: number) => formatCurrency(value, content.deal.currency, locale);
    const watermark = status === 'superseded'
        ? tp('supersededWatermark')
        : status != null && status !== 'final' ? tp('draftWatermark') : null;

    const lineItemsTable = content.lineItems.length > 0 ? (
        <div>
            <div className="sm:hidden print:hidden" data-testid="document-line-items-stacked">
                <ul className="divide-y divide-border border-y border-border">
                    {content.lineItems.map((item) => (
                        <li key={item.id} className="min-w-0 py-4">
                            <div className="min-w-0 font-medium text-foreground break-words">{item.name}</div>
                            {item.description && (
                                <div className="mt-1 min-w-0 text-xs text-muted-foreground break-words">
                                    {item.description}
                                </div>
                            )}
                            <div className="mt-1 text-xs text-muted-foreground">
                                {item.billingFrequency === 'recurring' ? tp('recurring') : tp('oneTime')}
                            </div>
                            <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-3">
                                <div className="min-w-0">
                                    <dt className="text-xs text-muted-foreground">{tp('columnQty')}</dt>
                                    <dd className="mt-0.5 text-sm tabular-nums">{item.quantity}</dd>
                                </div>
                                <div className="min-w-0 text-right">
                                    <dt className="text-xs text-muted-foreground">{tp('columnUnitPrice')}</dt>
                                    <dd className="mt-0.5 text-sm tabular-nums">{money(item.unitPrice)}</dd>
                                </div>
                                <div className="col-span-2 flex min-w-0 items-baseline justify-between gap-4 border-t border-border pt-3">
                                    <dt className="text-xs text-muted-foreground">{tp('columnLineTotal')}</dt>
                                    <dd className="min-w-0 text-right text-sm font-medium tabular-nums">
                                        {money(item.lineTotal)}
                                    </dd>
                                </div>
                            </dl>
                        </li>
                    ))}
                </ul>
                <dl className="mt-4 space-y-2">
                    <MobileTotalRow label={tp('subtotal')} value={money(content.totals.subtotal)} />
                    <MobileTotalRow label={tp('tax')} value={money(content.totals.tax)} />
                    {content.totals.recurringTotal > 0 && (
                        <>
                            <MobileTotalRow label={tp('oneTimeTotal')} value={money(content.totals.oneTimeTotal)} muted />
                            <MobileTotalRow label={tp('recurringTotal')} value={money(content.totals.recurringTotal)} muted />
                        </>
                    )}
                    <MobileTotalRow label={tp('grandTotal')} value={money(content.totals.grandTotal)} emphasis />
                </dl>
            </div>
            <div className="hidden sm:block print:block" data-testid="document-line-items-table">
                <table className="w-full table-fixed text-sm">
                    <thead>
                        <tr className="border-b border-border text-left text-xs uppercase tracking-[0.08em] text-muted-foreground">
                            <th className="py-2.5 pr-4 font-medium">{tp('columnItem')}</th>
                            <th className="w-20 px-3 py-2.5 text-right font-medium">{tp('columnQty')}</th>
                            <th className="w-32 px-3 py-2.5 text-right font-medium">{tp('columnUnitPrice')}</th>
                            <th className="w-32 py-2.5 pl-3 text-right font-medium">{tp('columnLineTotal')}</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-border">
                        {content.lineItems.map((item) => (
                            <tr key={item.id} className="align-top">
                                <td className="min-w-0 py-3 pr-4">
                                    <div className="font-medium text-foreground break-words">{item.name}</div>
                                    {item.description && (
                                        <div className="mt-0.5 text-xs text-muted-foreground break-words">{item.description}</div>
                                    )}
                                    <div className="mt-0.5 text-xs text-muted-foreground">
                                        {item.billingFrequency === 'recurring' ? tp('recurring') : tp('oneTime')}
                                    </div>
                                </td>
                                <td className="px-3 py-3 text-right tabular-nums">{item.quantity}</td>
                                <td className="px-3 py-3 text-right tabular-nums">{money(item.unitPrice)}</td>
                                <td className="py-3 pl-3 text-right font-medium tabular-nums">{money(item.lineTotal)}</td>
                            </tr>
                        ))}
                    </tbody>
                    <tfoot className="border-t border-border">
                        <TotalRow label={tp('subtotal')} value={money(content.totals.subtotal)} />
                        <TotalRow label={tp('tax')} value={money(content.totals.tax)} />
                        {content.totals.recurringTotal > 0 && (
                            <>
                                <TotalRow label={tp('oneTimeTotal')} value={money(content.totals.oneTimeTotal)} muted />
                                <TotalRow label={tp('recurringTotal')} value={money(content.totals.recurringTotal)} muted />
                            </>
                        )}
                        <TotalRow label={tp('grandTotal')} value={money(content.totals.grandTotal)} emphasis />
                    </tfoot>
                </table>
            </div>
        </div>
    ) : null;

    return (
        <div className="relative text-foreground">
            {watermark && (
                <div className="pointer-events-none absolute right-0 top-0 select-none text-[0.7rem] font-semibold uppercase tracking-[0.35em] text-muted-foreground/50">
                    {watermark}
                </div>
            )}

            <header className="mb-10 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between sm:gap-6">
                <div className="min-w-0">
                    <div className="text-xs font-medium uppercase tracking-[0.18em] text-muted-foreground">
                        {t(TYPE_KEY[type])}
                    </div>
                    <h1 className="mt-2 text-pretty text-3xl font-semibold tracking-tight text-foreground">
                        {title || content.sections.title || t('untitled')}
                    </h1>
                </div>
                {(version != null || generatedAt) && (
                    <div className="shrink-0 space-y-1 text-left text-xs text-muted-foreground sm:text-right">
                        {version != null && <div>{t('version', { version })}</div>}
                        {generatedAt && (
                            <div>
                                <span className="text-muted-foreground">{tp('generatedOn')} </span>
                                <span className="font-medium text-foreground">{formatUtcDateTime(generatedAt, locale)}</span>
                            </div>
                        )}
                    </div>
                )}
            </header>

            <section className="mb-10 grid grid-cols-1 gap-8 sm:grid-cols-2">
                <Party label={tp('from')} party={content.workspace} />
                <Party label={tp('to')} party={content.company} />
            </section>

            {content.owner?.name && (
                <p className="mb-8 text-sm text-muted-foreground">
                    {tp('preparedBy')} <span className="text-foreground">{content.owner.name}</span>
                </p>
            )}

            {content.body
                ? <div className="document-prose max-w-none text-sm leading-relaxed text-foreground">
                    <BodyNodes nodes={content.body.content ?? []} lineItemsTable={lineItemsTable} />
                    {lineItemsTable && !bodyHasLineItems(content.body) && (
                        <div className="my-6">{lineItemsTable}</div>
                    )}
                  </div>
                : (
                    <>
                        {content.sections.intro && (
                            <section className="mb-10 max-w-[70ch] whitespace-pre-line text-sm leading-relaxed text-foreground">
                                {content.sections.intro}
                            </section>
                        )}
                        {lineItemsTable && <section className="mb-8">{lineItemsTable}</section>}
                        {content.sections.terms && (
                            <section className="mb-8">
                                <h2 className="mb-2 text-xs font-semibold uppercase tracking-[0.12em] text-muted-foreground">
                                    {tp('terms')}
                                </h2>
                                <div className="max-w-[70ch] whitespace-pre-line text-sm leading-relaxed text-foreground">
                                    {content.sections.terms}
                                </div>
                            </section>
                        )}
                        {content.sections.footer && (
                            <footer className="mt-12 border-t border-border pt-4 text-xs text-muted-foreground">
                                <div className="whitespace-pre-line">{content.sections.footer}</div>
                            </footer>
                        )}
                    </>
                )}
        </div>
    );
}

function bodyHasLineItems(node: DocumentBodyNode): boolean {
    if (node.type === 'lineItems') return true;
    return (node.content ?? []).some(bodyHasLineItems);
}

function BodyNodes({ nodes, lineItemsTable }: { nodes: DocumentBodyNode[]; lineItemsTable: ReactNode }) {
    return (
        <>
            {nodes.map((node, index) => (
                <Fragment key={index}>{renderNode(node, lineItemsTable)}</Fragment>
            ))}
        </>
    );
}

function alignStyle(node: DocumentBodyNode): CSSProperties | undefined {
    const align = node.attrs?.textAlign;
    return typeof align === 'string' && align !== 'left' ? { textAlign: align as CSSProperties['textAlign'] } : undefined;
}

function renderNode(node: DocumentBodyNode, lineItemsTable: ReactNode): ReactNode {
    switch (node.type) {
        case 'paragraph':
            return (
                <p className="my-2.5" style={alignStyle(node)}>
                    {renderInline(node.content)}
                </p>
            );
        case 'heading': {
            const level = typeof node.attrs?.level === 'number' ? node.attrs.level : 2;
            const cls = level === 1
                ? 'mt-6 mb-2 text-xl font-semibold tracking-tight'
                : level === 2
                    ? 'mt-5 mb-2 text-lg font-semibold tracking-tight'
                    : 'mt-4 mb-1.5 text-base font-semibold';
            const Tag = (level === 1 ? 'h1' : level === 2 ? 'h2' : 'h3') as 'h1' | 'h2' | 'h3';
            return <Tag className={cls} style={alignStyle(node)}>{renderInline(node.content)}</Tag>;
        }
        case 'bulletList':
            return <ul className="my-2.5 list-disc space-y-1 pl-5">{(node.content ?? []).map((li, i) => <li key={i}>{renderListItem(li, lineItemsTable)}</li>)}</ul>;
        case 'orderedList':
            return <ol className="my-2.5 list-decimal space-y-1 pl-5">{(node.content ?? []).map((li, i) => <li key={i}>{renderListItem(li, lineItemsTable)}</li>)}</ol>;
        case 'blockquote':
            return <blockquote className="my-3 border-l-2 border-border pl-4 text-muted-foreground">{(node.content ?? []).map((child, i) => <Fragment key={i}>{renderNode(child, lineItemsTable)}</Fragment>)}</blockquote>;
        case 'codeBlock':
            return <pre className="my-3 overflow-x-auto rounded-lg bg-muted p-3 text-xs"><code>{renderInline(node.content)}</code></pre>;
        case 'horizontalRule':
            return <hr className="my-6 border-border" />;
        case 'lineItems':
            return lineItemsTable ? <div className="my-6">{lineItemsTable}</div> : null;
        default:
            return null;
    }
}

function renderListItem(item: DocumentBodyNode, lineItemsTable: ReactNode): ReactNode {
    return (item.content ?? []).map((child, index) =>
        child.type === 'paragraph'
            ? <Fragment key={index}>{renderInline(child.content)}</Fragment>
            : <Fragment key={index}>{renderNode(child, lineItemsTable)}</Fragment>);
}

function renderInline(nodes: DocumentBodyNode[] | undefined): ReactNode {
    if (!nodes) return null;
    return nodes.map((node, index) => {
        if (node.type === 'hardBreak') return <br key={index} />;
        if (node.type === 'mergeToken') {
            const token = typeof node.attrs?.token === 'string' ? node.attrs.token : '';
            return <Fragment key={index}>{`{{${token}}}`}</Fragment>;
        }
        if (node.type === 'text') return <Fragment key={index}>{applyMarks(node.text ?? '', node.marks)}</Fragment>;
        return null;
    });
}

function applyMarks(text: string, marks: DocumentBodyMark[] | undefined): ReactNode {
    if (!marks || marks.length === 0) return text;
    return marks.reduce<ReactNode>((acc, mark) => {
        switch (mark.type) {
            case 'bold':
                return <strong>{acc}</strong>;
            case 'italic':
                return <em>{acc}</em>;
            case 'strike':
                return <s>{acc}</s>;
            case 'code':
                return <code className="rounded bg-muted px-1 py-0.5 text-[0.85em]">{acc}</code>;
            case 'link': {
                const raw = typeof mark.attrs?.href === 'string' ? mark.attrs.href : '';
                const href = /^(https?:|mailto:|tel:|\/)/i.test(raw) ? raw : undefined;
                return <a href={href} className="text-brand underline underline-offset-2">{acc}</a>;
            }
            default:
                return acc;
        }
    }, text);
}

function Party({ label, party }: { label: string; party?: DocumentParty | null }) {
    if (!party?.name) return <div />;
    return (
        <div>
            <div className="mb-1.5 text-xs font-semibold uppercase tracking-[0.12em] text-muted-foreground">{label}</div>
            <div className="font-medium text-foreground">{party.name}</div>
            {party.address && (
                <div className="mt-0.5 whitespace-pre-line text-sm text-muted-foreground">{party.address}</div>
            )}
        </div>
    );
}

function TotalRow({ label, value, emphasis, muted }: { label: string; value: string; emphasis?: boolean; muted?: boolean }) {
    return (
        <tr className={muted ? 'text-muted-foreground' : ''}>
            <td className="py-1.5" colSpan={2} />
            <td className="px-3 py-1.5 text-right text-sm text-muted-foreground">{label}</td>
            <td className={`py-1.5 pl-3 text-right tabular-nums ${emphasis ? 'text-base font-semibold text-foreground' : 'text-sm'}`}>
                {value}
            </td>
        </tr>
    );
}

function MobileTotalRow({ label, value, emphasis, muted }: { label: string; value: string; emphasis?: boolean; muted?: boolean }) {
    return (
        <div className={`flex min-w-0 items-baseline justify-between gap-4 ${muted ? 'text-muted-foreground' : ''}`}>
            <dt className="text-xs text-muted-foreground">{label}</dt>
            <dd className={`min-w-0 text-right tabular-nums ${emphasis ? 'text-base font-semibold text-foreground' : 'text-sm'}`}>
                {value}
            </dd>
        </div>
    );
}
