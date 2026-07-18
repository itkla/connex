'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import {
    ArrowDownTrayIcon,
    CheckCircleIcon,
    ArchiveBoxXMarkIcon,
    TrashIcon,
    EllipsisHorizontalIcon,
    ChevronDownIcon,
    PlusIcon,
    DocumentTextIcon,
} from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
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

const STATUS_DOT: Record<DocumentStatus, string> = {
    draft: 'bg-chart-open',
    final: 'bg-chart-won',
    superseded: 'bg-muted-foreground',
};

/**
 * Generated-documents panel for a deal. Documents are immutable server-side snapshots; the client
 * generates a draft from a template, transitions its status, or opens a print view (browser
 * print-to-PDF) — it never edits a document's content or computes money.
 */
export default function DealDocuments({ dealId, initial }: Props) {
    const t = useTranslations('DealsDocuments');
    const locale = useLocale();
    const router = useRouter();
    const [documents, setDocuments] = useState<DealDocument[]>(initial);
    const [templates, setTemplates] = useState<DocumentTemplate[]>([]);
    const [busy, setBusy] = useState(false);

    useEffect(() => {
        getDocumentTemplates()
            .then((all) => setTemplates(all.filter((tpl) => tpl.active)))
            .catch(() => setTemplates([]));
    }, []);

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

    const openPdf = (doc: DealDocument) => {
        window.open(`/records/deals/${dealId}/documents/${doc.id}/print`, '_blank', 'noopener,noreferrer');
    };

    const generateMenu = (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <Button variant="brand" size="sm" disabled={busy}>
                    <PlusIcon className="size-4" />
                    {t('generate')}
                    <ChevronDownIcon className="size-3.5 opacity-70 transition-transform duration-150 group-data-[state=open]/button:rotate-180" />
                </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="min-w-56">
                {templates.length === 0 ? (
                    <DropdownMenuItem onSelect={() => router.push('/records/document-templates/new')}>
                        <PlusIcon className="size-4" />
                        {t('createTemplate')}
                    </DropdownMenuItem>
                ) : (
                    templates.map((tpl) => (
                        <DropdownMenuItem key={tpl.id} onSelect={() => generate(tpl)}>
                            <span className="flex w-full items-center justify-between gap-4">
                                <span className="truncate">{tpl.name}</span>
                                <span className="shrink-0 text-xs text-muted-foreground">{t(TYPE_KEY[tpl.type])}</span>
                            </span>
                        </DropdownMenuItem>
                    ))
                )}
            </DropdownMenuContent>
        </DropdownMenu>
    );

    return (
        <section>
            <div className="mb-3 flex items-center justify-between">
                <SectionHeader title={t('title')} />
                {documents.length > 0 && generateMenu}
            </div>

            {documents.length === 0 ? (
                <div className="flex flex-col items-center gap-4 rounded-2xl border border-dashed border-border bg-card px-6 py-12 text-center">
                    <div className="flex size-11 items-center justify-center rounded-full bg-muted text-muted-foreground">
                        <DocumentTextIcon className="size-5" />
                    </div>
                    <div className="space-y-1">
                        <p className="text-sm font-medium text-foreground">{t('emptyTitle')}</p>
                        <p className="mx-auto max-w-sm text-sm text-muted-foreground">{t('emptyBody')}</p>
                    </div>
                    {generateMenu}
                </div>
            ) : (
                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                    <table className="w-full text-sm">
                        <thead>
                            <tr className="border-b border-border text-left text-xs uppercase tracking-[0.08em] text-muted-foreground">
                                <th className="px-4 py-3 font-medium">{t('columnDocument')}</th>
                                <th className="w-28 px-4 py-3 font-medium">{t('columnStatus')}</th>
                                <th className="w-36 px-4 py-3 text-right font-medium">{t('columnTotal')}</th>
                                <th className="w-40 px-4 py-3 font-medium">{t('columnGenerated')}</th>
                                <th className="w-24 px-2 py-3" />
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                            {documents.map((doc) => (
                                <tr key={doc.id} className="transition-colors hover:bg-muted/50">
                                    <td className="px-4 py-3">
                                        <div className="font-medium text-foreground">{doc.title || t('untitled')}</div>
                                        <div className="text-xs text-muted-foreground">
                                            {t(TYPE_KEY[doc.type])} · {t('version', { version: doc.version })}
                                        </div>
                                    </td>
                                    <td className="px-4 py-3">
                                        <span className={`inline-flex items-center gap-1.5 rounded-full border border-border px-2 py-0.5 text-xs font-medium ${doc.status === 'superseded' ? 'text-muted-foreground' : 'text-foreground'}`}>
                                            <span className={`size-1.5 rounded-full ${STATUS_DOT[doc.status]}`} aria-hidden="true" />
                                            {t(`status_${doc.status}`)}
                                        </span>
                                    </td>
                                    <td className="px-4 py-3 text-right tabular-nums">
                                        {formatCurrency(doc.content.totals.grandTotal, doc.currency, locale)}
                                    </td>
                                    <td className="px-4 py-3 text-muted-foreground">
                                        {formatDateTime(doc.generatedAt, locale)}
                                    </td>
                                    <td className="px-2 py-3">
                                        <div className="flex items-center justify-end gap-1">
                                            <Button variant="outline" size="sm" onClick={() => openPdf(doc)} disabled={busy}>
                                                <ArrowDownTrayIcon className="size-4" />
                                                {t('pdf')}
                                            </Button>
                                            <DropdownMenu>
                                                <DropdownMenuTrigger asChild>
                                                    <Button variant="ghost" size="icon-xs" aria-label={t('actions')} disabled={busy}>
                                                        <EllipsisHorizontalIcon className="size-4" />
                                                    </Button>
                                                </DropdownMenuTrigger>
                                                <DropdownMenuContent align="end">
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
                                        </div>
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
