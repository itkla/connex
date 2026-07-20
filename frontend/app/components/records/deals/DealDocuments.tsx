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
    ShieldCheckIcon,
    XCircleIcon,
    ArrowUturnLeftIcon,
} from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import { Button } from '@/components/ui/button';
import {
    Dialog,
    DialogContent,
    DialogHeader,
    DialogTitle,
    DialogDescription,
    DialogFooter,
    DialogClose,
} from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
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
    getDealDocumentById,
    generateDealDocument,
    updateDealDocumentStatus,
    deleteDealDocument,
    requestDocumentApproval,
    decideDocumentApproval,
    cancelDocumentApproval,
} from '@/app/lib/api';
import type { DealDocument, DocumentClientStatus, DocumentStatus, DocumentTemplate, DocumentType } from '@/app/lib/types';

type Props = {
    dealId: number;
    initial: DealDocument[];
    canApprove: boolean;
    currentUserId: number;
};

type ApprovalAction = 'request' | 'approve' | 'reject';

const APPROVAL_DIALOG_KEYS: Record<ApprovalAction, { title: string; body: string; confirm: string }> = {
    request: { title: 'requestDialogTitle', body: 'requestDialogBody', confirm: 'requestConfirm' },
    approve: { title: 'approveDialogTitle', body: 'approveDialogBody', confirm: 'approveConfirm' },
    reject: { title: 'rejectDialogTitle', body: 'rejectDialogBody', confirm: 'rejectConfirm' },
};

const TYPE_KEY: Record<DocumentType, string> = {
    quote: 'typeQuote',
    proposal: 'typeProposal',
    order_form: 'typeOrderForm',
    contract: 'typeContract',
};

const STATUS_DOT: Record<DocumentStatus, string> = {
    draft: 'bg-chart-open',
    pending_approval: 'bg-risk-medium',
    approved: 'bg-chart-won',
    final: 'bg-chart-won',
    superseded: 'bg-muted-foreground',
};

/**
 * Generated-documents panel for a deal. Documents are immutable server-side snapshots; the client
 * generates a draft from a template, transitions its status, runs the approval flow (request /
 * approve / reject / cancel), or opens a print view (browser print-to-PDF) — it never edits a
 * document's content or computes money. The server owns the approval gate; this UI only reflects
 * `requiresApproval` and the caller's `DOCUMENT_APPROVE` permission.
 */
export default function DealDocuments({ dealId, initial, canApprove, currentUserId }: Props) {
    const t = useTranslations('DealsDocuments');
    const locale = useLocale();
    const router = useRouter();
    const [documents, setDocuments] = useState<DealDocument[]>(initial);
    const [templates, setTemplates] = useState<DocumentTemplate[]>([]);
    const [busy, setBusy] = useState(false);
    const [approvalDialog, setApprovalDialog] = useState<{ doc: DealDocument; action: ApprovalAction } | null>(null);
    const [approvalDialogOpen, setApprovalDialogOpen] = useState(false);
    const [comment, setComment] = useState('');

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

    const refreshDocument = async (documentId: number) => {
        const updated = await getDealDocumentById(dealId, documentId);
        setDocuments((prev) => prev.map((d) => (d.id === updated.id ? updated : d)));
    };

    const generate = (template: DocumentTemplate) => run(async () => {
        const created = await generateDealDocument(dealId, template.id);
        setDocuments((prev) => [created, ...prev]);
        toastSuccess(t('generated'));
    });

    const changeStatus = (doc: DealDocument, status: DocumentClientStatus) => run(async () => {
        const updated = await updateDealDocumentStatus(dealId, doc.id, status);
        setDocuments((prev) => prev.map((d) => (d.id === updated.id ? updated : d)));
    });

    const remove = (doc: DealDocument) => run(async () => {
        await deleteDealDocument(dealId, doc.id);
        setDocuments((prev) => prev.filter((d) => d.id !== doc.id));
        toastSuccess(t('deleted'));
    });

    const cancelRequest = (doc: DealDocument) => run(async () => {
        try {
            await cancelDocumentApproval(dealId, doc.id);
            toastSuccess(t('approvalCancelled'));
        } finally {
            await refreshDocument(doc.id).catch(() => undefined);
        }
    });

    const submitApprovalAction = () => {
        if (!approvalDialog) return;
        const { doc, action } = approvalDialog;
        const trimmed = comment.trim();
        return run(async () => {
            try {
                if (action === 'request') {
                    await requestDocumentApproval(dealId, doc.id, trimmed || null);
                    toastSuccess(t('approvalRequested'));
                } else {
                    await decideDocumentApproval(dealId, doc.id, action === 'approve' ? 'approved' : 'rejected', trimmed || null);
                    toastSuccess(action === 'approve' ? t('approvalApproved') : t('approvalRejected'));
                }
            } finally {
                await refreshDocument(doc.id).catch(() => undefined);
            }
            setApprovalDialogOpen(false);
        });
    };

    const openApprovalDialog = (doc: DealDocument, action: ApprovalAction) => {
        setComment('');
        setApprovalDialog({ doc, action });
        setApprovalDialogOpen(true);
    };

    const openPdf = (doc: DealDocument) => {
        window.open(`/records/deals/${dealId}/documents/${doc.id}/print`, '_blank', 'noopener,noreferrer');
    };

    const isRequester = (doc: DealDocument) => doc.latestApproval?.requestedBy === currentUserId;

    const canFinalize = (doc: DealDocument) =>
        (doc.status === 'draft' && !doc.requiresApproval) || doc.status === 'approved';

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
                    <DropdownMenuItem onSelect={() => router.push('/library/documents/new')}>
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

    const dialogKeys = approvalDialog ? APPROVAL_DIALOG_KEYS[approvalDialog.action] : null;

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
                                <th className="w-32 px-4 py-3 font-medium">{t('columnStatus')}</th>
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
                                        {doc.status === 'draft' && doc.latestApproval?.status === 'rejected' && (
                                            <div className="mt-1 text-xs text-destructive">
                                                {doc.latestApproval.decisionComment
                                                    ? t('rejectedWithComment', { comment: doc.latestApproval.decisionComment })
                                                    : t('rejectedNote')}
                                            </div>
                                        )}
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
                                                        <DropdownMenuItem onSelect={() => openApprovalDialog(doc, 'request')}>
                                                            <ShieldCheckIcon className="size-4" />{t('requestApproval')}
                                                        </DropdownMenuItem>
                                                    )}
                                                    {canFinalize(doc) && (
                                                        <DropdownMenuItem onSelect={() => changeStatus(doc, 'final')}>
                                                            <CheckCircleIcon className="size-4" />{t('markFinal')}
                                                        </DropdownMenuItem>
                                                    )}
                                                    {doc.status === 'pending_approval' && canApprove && (
                                                        <>
                                                            <DropdownMenuItem onSelect={() => openApprovalDialog(doc, 'approve')}>
                                                                <CheckCircleIcon className="size-4" />{t('approve')}
                                                            </DropdownMenuItem>
                                                            <DropdownMenuItem onSelect={() => openApprovalDialog(doc, 'reject')}>
                                                                <XCircleIcon className="size-4" />{t('reject')}
                                                            </DropdownMenuItem>
                                                        </>
                                                    )}
                                                    {doc.status === 'pending_approval' && isRequester(doc) && (
                                                        <DropdownMenuItem onSelect={() => cancelRequest(doc)}>
                                                            <ArrowUturnLeftIcon className="size-4" />{t('cancelRequest')}
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

            <Dialog open={approvalDialogOpen} onOpenChange={setApprovalDialogOpen}>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>{dialogKeys ? t(dialogKeys.title) : ''}</DialogTitle>
                        <DialogDescription>
                            {dialogKeys ? t(dialogKeys.body, { title: approvalDialog?.doc.title ?? '' }) : ''}
                        </DialogDescription>
                    </DialogHeader>
                    <div className="space-y-2">
                        <Label htmlFor="approval-comment">{t('commentLabel')}</Label>
                        <Textarea
                            id="approval-comment"
                            rows={3}
                            maxLength={1000}
                            value={comment}
                            placeholder={t('commentPlaceholder')}
                            onChange={(e) => setComment(e.target.value)}
                            disabled={busy}
                        />
                    </div>
                    <DialogFooter>
                        <DialogClose asChild>
                            <Button variant="outline" disabled={busy}>{t('dialogCancel')}</Button>
                        </DialogClose>
                        <Button
                            variant={approvalDialog?.action === 'reject' ? 'destructive' : 'brand'}
                            disabled={busy}
                            onClick={submitApprovalAction}
                        >
                            {busy
                                ? <Loader2Icon className="size-4 animate-spin" />
                                : dialogKeys ? t(dialogKeys.confirm) : ''}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </section>
    );
}
