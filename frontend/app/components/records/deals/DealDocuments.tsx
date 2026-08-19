'use client';

import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { useRouter } from 'next/navigation';
import { useReducedMotion } from 'motion/react';
import { useLocale, useTranslations } from 'next-intl';
import {
    ArrowDownTrayIcon,
    CheckCircleIcon,
    ArchiveBoxXMarkIcon,
    TrashIcon,
    EllipsisHorizontalIcon,
    PlusIcon,
    DocumentTextIcon,
    ShieldCheckIcon,
    UserPlusIcon,
    XCircleIcon,
    ArrowUturnLeftIcon,
} from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import { Button } from '@/components/ui/button';
import {
    Autocomplete,
    AutocompleteContent,
    AutocompleteEmpty,
    AutocompleteInput,
    AutocompleteItem,
    AutocompleteList,
} from '@/components/ui/autocomplete';
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
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { canDeleteOwnedRecord } from '@/app/lib/deletionPolicy';
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
    delegateDocumentApproval,
    cancelDocumentApproval,
    getDocumentApprovalDelegateCandidates,
} from '@/app/lib/api';
import type {
    ApprovalDelegate,
    DealDocument,
    DocumentApprovalStep,
    DocumentClientStatus,
    DocumentStatus,
    DocumentTemplate,
    DocumentType,
} from '@/app/lib/types';
import DocumentApprovalChain from './DocumentApprovalChain';
import { DEAL_DOCUMENTS_ANCHOR } from './dealLinks';

/**
 * Subscribes to same-document fragment changes. A fragment set by a client-side navigation arrives
 * in the first snapshot rather than as an event, so this only has to carry the case where the
 * fragment changes while the panel stays mounted.
 */
function subscribeToHash(onChange: () => void): () => void {
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
}

function hashSnapshot(): string {
    return window.location.hash;
}

type Props = {
    dealId: number;
    initial: DealDocument[];
    canApprove: boolean;
    canDeleteDocuments: boolean;
    currentUserId: number;
};

type ApprovalAction = 'request' | 'approve' | 'reject' | 'delegate';

const APPROVAL_DIALOG_KEYS: Record<ApprovalAction, { title: string; body: string; confirm: string }> = {
    request: { title: 'requestDialogTitle', body: 'requestDialogBody', confirm: 'requestConfirm' },
    approve: { title: 'approveDialogTitle', body: 'approveDialogBody', confirm: 'approveConfirm' },
    reject: { title: 'rejectDialogTitle', body: 'rejectDialogBody', confirm: 'rejectConfirm' },
    delegate: { title: 'delegateDialogTitle', body: 'delegateDialogBody', confirm: 'delegateConfirm' },
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

const delegateOption = (candidate: ApprovalDelegate) =>
    `${candidate.displayName || candidate.username} (${candidate.email})`;

function terminatedApproval(doc: DealDocument) {
    const approval = doc.latestApproval;
    if (!approval?.outcomeReason) return null;
    return approval.status === 'invalidated'
        || approval.status === 'unsatisfiable'
        || approval.status === 'expired'
        ? approval
        : null;
}

/**
 * Generated-documents panel for a deal. Documents are immutable server-side snapshots; the client
 * generates a draft from a template, transitions its status, runs the approval flow (request /
 * approve / reject / cancel), or opens a print view (browser print-to-PDF) — it never edits a
 * document's content or computes money. The server owns the approval gate; this UI only reflects
 * `requiresApproval` and the caller's `DOCUMENT_APPROVE` permission.
 */
export default function DealDocuments({
    dealId,
    initial,
    canApprove,
    canDeleteDocuments,
    currentUserId,
}: Props) {
    const t = useTranslations('DealsDocuments');
    const locale = useLocale();
    const router = useRouter();
    const reduceMotion = useReducedMotion() ?? false;
    const sectionRef = useRef<HTMLElement>(null);
    const scrolledForHash = useRef<string | null>(null);
    const hash = useSyncExternalStore(subscribeToHash, hashSnapshot, () => '');
    const { activeWorkspace } = useWorkspace();
    const [documents, setDocuments] = useState<DealDocument[]>(initial);
    const [templates, setTemplates] = useState<DocumentTemplate[]>([]);
    const [busy, setBusy] = useState(false);
    const [approvalDialog, setApprovalDialog] = useState<
        { doc: DealDocument; action: ApprovalAction; stepId: number | null } | null>(null);
    const [approvalDialogOpen, setApprovalDialogOpen] = useState(false);
    const [comment, setComment] = useState('');
    const [delegateCandidates, setDelegateCandidates] = useState<ApprovalDelegate[]>([]);
    const [delegateCandidatesLoading, setDelegateCandidatesLoading] = useState(false);
    const [delegateCandidatesError, setDelegateCandidatesError] = useState(false);
    const [delegateQuery, setDelegateQuery] = useState('');
    const [delegateUserId, setDelegateUserId] = useState<number | null>(null);

    useEffect(() => {
        getDocumentTemplates()
            .then((all) => setTemplates(all.filter((tpl) => tpl.active)))
            .catch(() => setTemplates([]));
    }, []);

    useEffect(() => {
        if (hash !== `#${DEAL_DOCUMENTS_ANCHOR}`) scrolledForHash.current = null;
    }, [hash]);

    useEffect(() => {
        if (hash !== `#${DEAL_DOCUMENTS_ANCHOR}` || scrolledForHash.current === hash) return;
        const section = sectionRef.current;
        if (!section) return;
        scrolledForHash.current = hash;
        section.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' });
    }, [hash, reduceMotion]);

    useEffect(() => {
        if (
            !approvalDialogOpen
            || approvalDialog?.action !== 'delegate'
            || approvalDialog.stepId == null
        ) return;
        let cancelled = false;
        getDocumentApprovalDelegateCandidates(dealId, approvalDialog.doc.id, approvalDialog.stepId)
            .then((candidates) => {
                if (!cancelled) setDelegateCandidates(candidates);
            })
            .catch(() => {
                if (!cancelled) {
                    setDelegateCandidates([]);
                    setDelegateCandidatesError(true);
                }
            })
            .finally(() => {
                if (!cancelled) setDelegateCandidatesLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [approvalDialog, approvalDialogOpen, dealId]);

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
                } else if (action === 'delegate') {
                    if (delegateUserId == null || approvalDialog.stepId == null) return;
                    await delegateDocumentApproval(
                        dealId,
                        doc.id,
                        approvalDialog.stepId,
                        delegateUserId,
                        trimmed || null,
                    );
                    toastSuccess(t('approvalDelegated'));
                } else {
                    await decideDocumentApproval(
                        dealId,
                        doc.id,
                        action === 'approve' ? 'approved' : 'rejected',
                        trimmed || null,
                        approvalDialog.stepId,
                    );
                    toastSuccess(action === 'approve' ? t('approvalApproved') : t('approvalRejected'));
                }
            } finally {
                await refreshDocument(doc.id).catch(() => undefined);
            }
            setApprovalDialogOpen(false);
        });
    };

    const openApprovalDialog = (doc: DealDocument, action: ApprovalAction, stepId: number | null = null) => {
        setComment('');
        setDelegateQuery('');
        setDelegateUserId(null);
        if (action === 'delegate') {
            setDelegateCandidates([]);
            setDelegateCandidatesLoading(true);
            setDelegateCandidatesError(false);
        }
        setApprovalDialog({ doc, action, stepId });
        setApprovalDialogOpen(true);
    };

    const openPdf = (doc: DealDocument) => {
        window.open(`/records/deals/${dealId}/documents/${doc.id}/print`, '_blank', 'noopener,noreferrer');
    };

    const isRequester = (doc: DealDocument) => doc.latestApproval?.requestedBy === currentUserId;

    const actionableStep = (doc: DealDocument): DocumentApprovalStep | null => {
        const approval = doc.latestApproval;
        if (!canApprove || !approval || approval.status !== 'pending' || doc.status !== 'pending_approval') {
            return null;
        }
        let candidate: DocumentApprovalStep | null = null;
        for (const step of approval.steps) {
            if (step.status !== 'active') continue;
            if (!step.effectiveApproverIds.includes(currentUserId)) continue;
            if (candidate === null || step.stepOrder < candidate.stepOrder) candidate = step;
        }
        return candidate;
    };

    const canFinalize = (doc: DealDocument) =>
        (doc.status === 'draft' && !doc.requiresApproval) || doc.status === 'approved';

    const generateMenu = (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <Button variant="brand" size="toolbar" menu disabled={busy}>
                    <PlusIcon className="size-4" />
                    {t('generate')}
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
    const eligibleDelegates = approvalDialog?.action === 'delegate' ? delegateCandidates : [];

    return (
        <section id={DEAL_DOCUMENTS_ANCHOR} ref={sectionRef}>
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
                <div className="overflow-x-auto rounded-2xl border border-border bg-card">
                    <table className="w-full min-w-[32rem] text-sm">
                        <thead>
                            <tr className="border-b border-border text-left text-xs uppercase tracking-[0.08em] text-muted-foreground">
                                <th className="px-4 py-3 font-medium">{t('columnDocument')}</th>
                                <th className="w-32 px-4 py-3 font-medium">{t('columnStatus')}</th>
                                <th className="w-36 px-4 py-3 text-right font-medium">{t('columnTotal')}</th>
                                <th className="hidden w-40 px-4 py-3 font-medium md:table-cell">{t('columnGenerated')}</th>
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
                                        {doc.status === 'pending_approval' && doc.latestApproval && (
                                            <DocumentApprovalChain
                                                approval={doc.latestApproval}
                                                activeStepId={actionableStep(doc)?.id ?? null}
                                            />
                                        )}
                                        {doc.status === 'draft' && doc.latestApproval?.status === 'rejected' && (
                                            <div className="mt-1 text-xs text-destructive">
                                                {doc.latestApproval.decisionComment
                                                    ? t('rejectedWithComment', { comment: doc.latestApproval.decisionComment })
                                                    : t('rejectedNote')}
                                            </div>
                                        )}
                                        {doc.status === 'draft' && terminatedApproval(doc) && (
                                            <div className="mt-1 text-xs text-destructive">
                                                {t(`outcome_${terminatedApproval(doc)!.outcomeReason!}`)}
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
                                    <td className="hidden px-4 py-3 text-muted-foreground md:table-cell">
                                        {formatDateTime(doc.generatedAt, locale)}
                                    </td>
                                    <td className="px-2 py-3">
                                        <div className="flex items-center justify-end gap-1">
                                            <Button variant="outline" size="toolbar" onClick={() => openPdf(doc)} disabled={busy}>
                                                <ArrowDownTrayIcon className="size-4" />
                                                <span className="hidden sm:inline">{t('pdf')}</span>
                                            </Button>
                                            <DropdownMenu>
                                                <DropdownMenuTrigger asChild>
                                                    <Button variant="ghost" size="icon-inline" aria-label={t('actions')} disabled={busy}>
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
                                                    {actionableStep(doc) && (
                                                        <>
                                                            <DropdownMenuItem
                                                                onSelect={() => openApprovalDialog(doc, 'approve', actionableStep(doc)?.id ?? null)}
                                                            >
                                                                <CheckCircleIcon className="size-4" />{t('approve')}
                                                            </DropdownMenuItem>
                                                            <DropdownMenuItem
                                                                onSelect={() => openApprovalDialog(doc, 'reject', actionableStep(doc)?.id ?? null)}
                                                            >
                                                                <XCircleIcon className="size-4" />{t('reject')}
                                                            </DropdownMenuItem>
                                                            <DropdownMenuItem
                                                                onSelect={() => openApprovalDialog(doc, 'delegate', actionableStep(doc)?.id ?? null)}
                                                            >
                                                                <UserPlusIcon className="size-4" />{t('delegate')}
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
                                                    {doc.status === 'draft'
                                                        && canDeleteDocuments
                                                        && canDeleteOwnedRecord(doc.createdBy, currentUserId, activeWorkspace?.role) && (
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
                    {approvalDialog?.action === 'delegate' && (
                        <div className="space-y-2">
                            <Label htmlFor="approval-delegate-member">{t('delegateMemberLabel')}</Label>
                            <Autocomplete
                                items={eligibleDelegates}
                                value={delegateQuery}
                                onValueChange={(value, eventDetails) => {
                                    if (eventDetails.reason === 'escape-key') {
                                        eventDetails.allowPropagation();
                                        return;
                                    }
                                    const picked = eligibleDelegates.find(
                                        (member) => delegateOption(member) === value,
                                    );
                                    setDelegateQuery(value);
                                    setDelegateUserId(picked?.id ?? null);
                                }}
                                mode="list"
                                openOnInputClick
                            >
                                <AutocompleteInput
                                    id="approval-delegate-member"
                                    placeholder={delegateCandidatesLoading
                                        ? t('delegateMemberLoading')
                                        : t('delegateMemberPlaceholder')}
                                    aria-label={t('delegateMemberLabel')}
                                    disabled={busy || delegateCandidatesLoading || delegateCandidatesError}
                                />
                                <AutocompleteContent>
                                    <AutocompleteEmpty>{t('delegateMemberNoMatches')}</AutocompleteEmpty>
                                    <AutocompleteList>
                                        {(member: ApprovalDelegate) => (
                                            <AutocompleteItem key={member.id} value={delegateOption(member)}>
                                                {delegateOption(member)}
                                            </AutocompleteItem>
                                        )}
                                    </AutocompleteList>
                                </AutocompleteContent>
                            </Autocomplete>
                            {delegateCandidatesError && (
                                <p role="alert" className="text-xs text-destructive">
                                    {t('delegateMemberLoadFailed')}
                                </p>
                            )}
                        </div>
                    )}
                    <div className="space-y-2">
                        <Label htmlFor="approval-comment">{t('commentLabel')}</Label>
                        <Textarea
                            id="approval-comment"
                            rows={3}
                            maxLength={approvalDialog?.action === 'delegate' ? 500 : 1000}
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
                            disabled={busy || (approvalDialog?.action === 'delegate'
                                && (delegateCandidatesLoading
                                    || delegateCandidatesError
                                    || delegateUserId == null))}
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
