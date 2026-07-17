'use client';

import { useEffect } from 'react';
import { useLocale, useTranslations } from 'next-intl';

import { Button } from '@/components/ui/button';
import { formatCurrency, formatDateTime } from '@/app/lib/utils';
import type { DealDocument, DocumentParty, DocumentType } from '@/app/lib/types';

const TYPE_KEY: Record<DocumentType, string> = {
    quote: 'typeQuote',
    proposal: 'typeProposal',
    order_form: 'typeOrderForm',
    contract: 'typeContract',
};

/**
 * Print-ready rendering of an immutable generated document. Opened in its own tab and printed via
 * the browser (print-to-PDF) — the same mechanism the reports feature uses, so CJK glyphs render
 * through the loaded web font and the output stays vector and selectable.
 */
export default function DocumentPaper({ document: doc }: { document: DealDocument | null }) {
    const t = useTranslations('DealsDocuments');
    const tp = useTranslations('DealsDocuments.print');
    const locale = useLocale();

    useEffect(() => {
        if (!doc) return;
        let cancelled = false;
        const trigger = () => { if (!cancelled) window.print(); };
        const fonts = (window.document as Document & { fonts?: FontFaceSet }).fonts;
        const ready = fonts?.ready ?? Promise.resolve();
        const timer = window.setTimeout(() => ready.then(trigger).catch(trigger), 350);
        return () => { cancelled = true; window.clearTimeout(timer); };
    }, [doc]);

    if (!doc) {
        return (
            <div className="mx-auto max-w-2xl px-6 py-24 text-center text-sm text-muted-foreground">
                {tp('notFound')}
            </div>
        );
    }

    const { content } = doc;
    const money = (value: number) => formatCurrency(value, doc.currency, locale);
    const watermark = doc.status === 'draft'
        ? tp('draftWatermark')
        : doc.status === 'superseded' ? tp('supersededWatermark') : null;

    return (
        <div className="document-page min-h-full bg-muted/40 px-4 py-10 print:bg-white print:p-0">
            <div className="document-controls mx-auto mb-6 flex max-w-[52rem] items-center justify-end">
                <Button variant="brand" onClick={() => window.print()}>
                    {tp('printButton')}
                </Button>
            </div>

            <article className="document-paper relative mx-auto max-w-[52rem] overflow-hidden rounded-2xl border border-border bg-card px-12 py-14 text-foreground shadow-sm print:rounded-none print:border-0 print:px-0 print:py-0 print:shadow-none">
                {watermark && (
                    <div className="pointer-events-none absolute right-10 top-12 select-none text-xs font-semibold uppercase tracking-[0.35em] text-muted-foreground/50">
                        {watermark}
                    </div>
                )}

                <header className="mb-10 flex items-start justify-between gap-6">
                    <div>
                        <div className="text-xs font-medium uppercase tracking-[0.2em] text-muted-foreground">
                            {t(TYPE_KEY[doc.type])}
                        </div>
                        <h1 className="mt-2 text-3xl font-semibold tracking-tight text-foreground">
                            {content.sections.title || doc.title || t('untitled')}
                        </h1>
                    </div>
                    <div className="shrink-0 text-right text-xs text-muted-foreground">
                        <div>{t('version', { version: doc.version })}</div>
                        <div className="mt-1">{tp('generatedOn')}</div>
                        <div className="font-medium text-foreground">{formatDateTime(doc.generatedAt, locale)}</div>
                    </div>
                </header>

                <section className="mb-10 grid grid-cols-2 gap-8 text-sm">
                    <Party label={tp('from')} party={content.workspace} />
                    <Party label={tp('to')} party={content.company} />
                </section>

                {content.owner?.name && (
                    <section className="mb-8 text-sm text-muted-foreground">
                        {tp('preparedBy')} <span className="text-foreground">{content.owner.name}</span>
                    </section>
                )}

                {content.sections.intro && (
                    <section className="mb-10 whitespace-pre-line text-sm leading-relaxed text-foreground">
                        {content.sections.intro}
                    </section>
                )}

                {content.lineItems.length > 0 && (
                    <section className="mb-8">
                        <table className="w-full text-sm">
                            <thead>
                                <tr className="border-b border-border text-left text-xs uppercase tracking-[0.08em] text-muted-foreground">
                                    <th className="py-2.5 pr-4 font-medium">{tp('columnItem')}</th>
                                    <th className="w-20 py-2.5 px-3 text-right font-medium">{tp('columnQty')}</th>
                                    <th className="w-32 py-2.5 px-3 text-right font-medium">{tp('columnUnitPrice')}</th>
                                    <th className="w-32 py-2.5 pl-3 text-right font-medium">{tp('columnLineTotal')}</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {content.lineItems.map((item) => (
                                    <tr key={item.id} className="align-top">
                                        <td className="py-3 pr-4">
                                            <div className="font-medium text-foreground">{item.name}</div>
                                            {item.description && (
                                                <div className="mt-0.5 text-xs text-muted-foreground">{item.description}</div>
                                            )}
                                            <div className="mt-0.5 text-xs text-muted-foreground">
                                                {item.billingFrequency === 'recurring' ? tp('recurring') : tp('oneTime')}
                                            </div>
                                        </td>
                                        <td className="py-3 px-3 text-right tabular-nums">{item.quantity}</td>
                                        <td className="py-3 px-3 text-right tabular-nums">{money(item.unitPrice)}</td>
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
                    </section>
                )}

                {content.sections.terms && (
                    <section className="mb-8">
                        <h2 className="mb-2 text-xs font-semibold uppercase tracking-[0.12em] text-muted-foreground">
                            {tp('terms')}
                        </h2>
                        <div className="whitespace-pre-line text-sm leading-relaxed text-foreground">
                            {content.sections.terms}
                        </div>
                    </section>
                )}

                {content.sections.footer && (
                    <footer className="mt-12 border-t border-border pt-4 text-xs text-muted-foreground">
                        <div className="whitespace-pre-line">{content.sections.footer}</div>
                    </footer>
                )}
            </article>
        </div>
    );
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
        <tr className={emphasis ? 'text-foreground' : muted ? 'text-muted-foreground' : ''}>
            <td className="py-1.5" colSpan={2} />
            <td className="py-1.5 px-3 text-right text-sm text-muted-foreground">{label}</td>
            <td className={`py-1.5 pl-3 text-right tabular-nums ${emphasis ? 'text-base font-semibold text-foreground' : 'text-sm'}`}>
                {value}
            </td>
        </tr>
    );
}
