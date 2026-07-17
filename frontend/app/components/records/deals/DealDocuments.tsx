'use client';

import { useEffect, useMemo, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import {
    ArrowDownTrayIcon,
    CheckCircleIcon,
    ArchiveBoxXMarkIcon,
    TrashIcon,
    EllipsisHorizontalIcon,
} from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import {
    Combobox,
    ComboboxContent,
    ComboboxEmpty,
    ComboboxInput,
    ComboboxItem,
    ComboboxList,
} from '@/components/ui/combobox';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { formatCurrency, formatDateTime } from '@/app/lib/utils';
import {
    getDocumentTemplates,
    generateDealDocument,
    updateDealDocumentStatus,
    deleteDealDocument,
} from '@/app/lib/api';
import type { DealDocument, DocumentStatus, DocumentTemplate, DocumentType } from '@/app/lib/types';

type Props = {
    dealId: number;
    initial: DealDocument[];
};

const TYPE_KEY: Record<DocumentType, string> = {
    quote: 'typeQuote',
    proposal: 'typeProposal',
    order_form: 'typeOrderForm',
    contract: 'typeContract',
};

const STATUS_VARIANT: Record<DocumentStatus, 'secondary' | 'default' | 'outline'> = {
    draft: 'secondary',
    final: 'default',
    superseded: 'outline',
};

/**
 * Generated-documents panel for a deal. Documents are immutable server-side snapshots; the client
 * generates a draft from a template, transitions its status, or opens a print view (browser
 * print-to-PDF) — it never edits a document's content.
 */
export default function DealDocuments({ dealId, initial }: Props) {
    const t = useTranslations('DealsDocuments');
    const locale = useLocale();
    const [documents, setDocuments] = useState<DealDocument[]>(initial);
    const [templates, setTemplates] = useState<DocumentTemplate[]>([]);
    const [busy, setBusy] = useState(false);

    useEffect(() => {
        getDocumentTemplates()
            .then((all) => setTemplates(all.filter((tpl) => tpl.active)))
            .catch(() => setTemplates([]));
    }, []);

    const templateItems = useMemo(() => templates, [templates]);

    const generate = (template: DocumentTemplate) => run(async () => {
        const created = await generateDealDocument(dealId, template.id);
        setDocuments((prev) => [created, ...prev]);
        toastSuccess(t('generated'));
    });

    const changeStatus = (doc: DealDocument, status: DocumentStatus) => run(async () => {
        const updated = await updateDealDocumentStatus(dealId, doc.id, status);
        setDocuments((prev) => prev.map((d) => (d.id === updated.id ? updated : d)));
    });

    const remove = (doc: DealDocument) => run(async () => {
        await deleteDealDocument(dealId, doc.id);
        setDocuments((prev) => prev.filter((d) => d.id !== doc.id));
        toastSuccess(t('deleted'));
    });

    const run = async (op: () => Promise<void>) => {
        setBusy(true);
        try {
            await op();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('actionFailed'));
        } finally {
            setBusy(false);
        }
    };

    const openPdf = (doc: DealDocument) => {
        window.open(`/records/deals/${dealId}/documents/${doc.id}/print`, '_blank', 'noopener,noreferrer');
    };

    return (
        <section>
            <div className="mb-3 flex items-center justify-between">
                <SectionHeader title={t('title')} />
                <Combobox
                    items={templateItems}
                    itemToStringLabel={(tpl: DocumentTemplate) => tpl.name}
                    value={null}
                    onValueChange={(tpl) => { if (tpl) generate(tpl as DocumentTemplate); }}
                >
                    <ComboboxInput placeholder={t('generateFromTemplate')} className="w-60" disabled={busy} />
                    <ComboboxContent>
                        <ComboboxList>
                            <ComboboxEmpty>{t('noTemplates')}</ComboboxEmpty>
                            {templateItems.map((tpl) => (
                                <ComboboxItem key={tpl.id} value={tpl}>
                                    <span className="flex w-full items-center justify-between gap-3">
                                        <span className="truncate">{tpl.name}</span>
                                        <span className="shrink-0 text-xs text-muted-foreground">{t(TYPE_KEY[tpl.type])}</span>
                                    </span>
                                </ComboboxItem>
                            ))}
                        </ComboboxList>
                    </ComboboxContent>
                </Combobox>
            </div>

            {documents.length === 0 ? (
                <div className="rounded-2xl border border-border bg-card px-6 py-12 text-center text-sm text-muted-foreground">
                    {t('empty')}
                </div>
            ) : (
                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                    <table className="w-full text-sm">
                        <thead>
                            <tr className="border-b border-border text-left text-xs uppercase tracking-[0.08em] text-muted-foreground">
                                <th className="px-4 py-3 font-medium">{t('columnDocument')}</th>
                                <th className="w-28 px-4 py-3 font-medium">{t('columnType')}</th>
                                <th className="w-28 px-4 py-3 font-medium">{t('columnStatus')}</th>
                                <th className="w-40 px-4 py-3 font-medium text-right">{t('columnTotal')}</th>
                                <th className="w-44 px-4 py-3 font-medium">{t('columnGenerated')}</th>
                                <th className="w-10 px-2 py-3" />
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                            {documents.map((doc) => (
                                <tr key={doc.id} className="transition-colors hover:bg-muted/50">
                                    <td className="px-4 py-3">
                                        <div className="font-medium text-foreground">{doc.title || t('untitled')}</div>
                                        <div className="text-xs text-muted-foreground">{t('version', { version: doc.version })}</div>
                                    </td>
                                    <td className="px-4 py-3 text-muted-foreground">{t(TYPE_KEY[doc.type])}</td>
                                    <td className="px-4 py-3">
                                        <Badge variant={STATUS_VARIANT[doc.status]}>{t(`status_${doc.status}`)}</Badge>
                                    </td>
                                    <td className="px-4 py-3 text-right tabular-nums">
                                        {formatCurrency(doc.content.totals.grandTotal, doc.currency, locale)}
                                    </td>
                                    <td className="px-4 py-3 text-muted-foreground">
                                        {formatDateTime(doc.generatedAt, locale)}
                                    </td>
                                    <td className="px-2 py-3 text-right">
                                        <DropdownMenu>
                                            <DropdownMenuTrigger asChild>
                                                <Button variant="ghost" size="icon-xs" aria-label={t('actions')} disabled={busy}>
                                                    <EllipsisHorizontalIcon className="size-4" />
                                                </Button>
                                            </DropdownMenuTrigger>
                                            <DropdownMenuContent align="end">
                                                <DropdownMenuItem onSelect={() => openPdf(doc)}>
                                                    <ArrowDownTrayIcon className="size-4" />{t('downloadPdf')}
                                                </DropdownMenuItem>
                                                {doc.status === 'draft' && (
                                                    <DropdownMenuItem onSelect={() => changeStatus(doc, 'final')}>
                                                        <CheckCircleIcon className="size-4" />{t('markFinal')}
                                                    </DropdownMenuItem>
                                                )}
                                                {doc.status !== 'superseded' && (
                                                    <DropdownMenuItem onSelect={() => changeStatus(doc, 'superseded')}>
                                                        <ArchiveBoxXMarkIcon className="size-4" />{t('markSuperseded')}
                                                    </DropdownMenuItem>
                                                )}
                                                {doc.status === 'draft' && (
                                                    <>
                                                        <DropdownMenuSeparator />
                                                        <DropdownMenuItem variant="destructive" onSelect={() => remove(doc)}>
                                                            <TrashIcon className="size-4" />{t('delete')}
                                                        </DropdownMenuItem>
                                                    </>
                                                )}
                                            </DropdownMenuContent>
                                        </DropdownMenu>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </section>
    );
}
