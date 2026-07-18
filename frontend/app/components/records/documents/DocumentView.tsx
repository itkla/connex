'use client';

import { useLocale, useTranslations } from 'next-intl';

import { formatCurrency, formatDateTime } from '@/app/lib/utils';
import type { DocumentContent, DocumentParty, DocumentStatus, DocumentType } from '@/app/lib/types';

const TYPE_KEY: Record<DocumentType, string> = {
    quote: 'typeQuote',
    proposal: 'typeProposal',
    order_form: 'typeOrderForm',
    contract: 'typeContract',
};

type Props = {
    content: DocumentContent;
    type: DocumentType;
    status?: DocumentStatus;
    version?: number;
    generatedAt?: string;
};

/**
 * Canonical rendering of a commercial document's resolved content. Shared by the template builder's
 * live preview (sample data) and the printable output (real, immutable snapshot), so what an author
 * builds is exactly what a deal owner sends. Presentational only — it renders the paper body, not
 * page or print chrome, and never computes money (totals come pre-computed in {@link content}).
 */
export default function DocumentView({ content, type, status, version, generatedAt }: Props) {
    const t = useTranslations('DealsDocuments');
    const tp = useTranslations('DealsDocuments.print');
    const locale = useLocale();
    const money = (value: number) => formatCurrency(value, content.deal.currency, locale);
    const watermark = status === 'superseded'
        ? tp('supersededWatermark')
        : status != null && status !== 'final' ? tp('draftWatermark') : null;

    return (
        <div className="relative text-foreground">
            {watermark && (
                <div className="pointer-events-none absolute right-0 top-0 select-none text-[0.7rem] font-semibold uppercase tracking-[0.35em] text-muted-foreground/50">
                    {watermark}
                </div>
            )}

            <header className="mb-10 flex items-start justify-between gap-6">
                <div className="min-w-0">
                    <div className="text-xs font-medium uppercase tracking-[0.18em] text-muted-foreground">
                        {t(TYPE_KEY[type])}
                    </div>
                    <h1 className="mt-2 text-pretty text-3xl font-semibold tracking-tight text-foreground">
                        {content.sections.title || t('untitled')}
                    </h1>
                </div>
                {(version != null || generatedAt) && (
                    <div className="shrink-0 space-y-1 text-right text-xs text-muted-foreground">
                        {version != null && <div>{t('version', { version })}</div>}
                        {generatedAt && (
                            <div>
                                <span className="text-muted-foreground">{tp('generatedOn')} </span>
                                <span className="font-medium text-foreground">{formatDateTime(generatedAt, locale)}</span>
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

            {content.sections.intro && (
                <section className="mb-10 max-w-[70ch] whitespace-pre-line text-sm leading-relaxed text-foreground">
                    {content.sections.intro}
                </section>
            )}

            {content.lineItems.length > 0 && (
                <section className="mb-8">
                    <table className="w-full text-sm">
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
                                    <td className="py-3 pr-4">
                                        <div className="font-medium text-foreground">{item.name}</div>
                                        {item.description && (
                                            <div className="mt-0.5 text-xs text-muted-foreground">{item.description}</div>
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
                </section>
            )}

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
        <tr className={muted ? 'text-muted-foreground' : ''}>
            <td className="py-1.5" colSpan={2} />
            <td className="px-3 py-1.5 text-right text-sm text-muted-foreground">{label}</td>
            <td className={`py-1.5 pl-3 text-right tabular-nums ${emphasis ? 'text-base font-semibold text-foreground' : 'text-sm'}`}>
                {value}
            </td>
        </tr>
    );
}
