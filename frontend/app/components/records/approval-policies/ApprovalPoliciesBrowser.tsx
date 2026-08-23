'use client';

import { useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import { PlusIcon, PencilIcon, TrashIcon, EllipsisHorizontalIcon, ShieldCheckIcon, DocumentDuplicateIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import Rise from '@/app/components/motion/Rise';
import SectionHeader from '@/app/components/dashboard/SectionHeader';
import { SearchField } from '@/app/components/filters';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import ApprovalPolicyDialog from '@/app/components/records/approval-policies/ApprovalPolicyDialog';
import { PageHeader } from '@/app/components/PageHeader';
import { PageShell } from '@/app/components/PageShell';
import { SettingsSection } from '@/app/components/settings/SettingsSection';
import { deleteApprovalPolicy } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { formatCurrency } from '@/app/lib/utils';
import type { ApprovalPolicy, DocumentType } from '@/app/lib/types';

const TYPE_KEY: Record<DocumentType, string> = {
    quote: 'typeQuote',
    proposal: 'typeProposal',
    order_form: 'typeOrderForm',
    contract: 'typeContract',
};

/**
 * Which of the browser's two homes is rendering it while #1340 migrates the workspace destinations.
 *
 * - `page` is `/records/approval-policies` exactly as it ships: its own route, its own shell, its
 *   own page header.
 * - `section` is the approval-policies section of CRM configuration. The settings layout already
 *   owns the shell, and the page is already one outline of section headings, so the browser trades
 *   its page header for a section heading of the same name and keeps both of its actions.
 */
export type ApprovalPoliciesPresentation = 'page' | 'section';

/**
 * The shell the browser stands in: its own on its own route, and none inside a settings page whose
 * layout already supplies one. A second shell would re-pad and re-clamp content that is already
 * inside one.
 */
function PolicyShell({
    presentation,
    children,
}: {
    presentation: ApprovalPoliciesPresentation;
    children: React.ReactNode;
}) {
    if (presentation === 'section') return <div className="flex flex-col gap-6">{children}</div>;
    return <PageShell>{children}</PageShell>;
}

/**
 * Workspace-scoped approval-policy admin: a searchable list with a dialog editor. Policies gate
 * finalization of generated deal documents server-side; this surface only configures them.
 */

export default function ApprovalPoliciesBrowser({
    policies: initial,
    presentation = 'page',
}: {
    policies: ApprovalPolicy[];
    presentation?: ApprovalPoliciesPresentation;
}) {
    const t = useTranslations('ApprovalPoliciesBrowser');
    const tf = useTranslations('Filters');
    const locale = useLocale();
    const router = useRouter();
    const [policies, setPolicies] = useState(initial);
    const [query, setQuery] = useState('');
    const [editorTarget, setEditorTarget] = useState<ApprovalPolicy | null>(null);
    const [editorOpen, setEditorOpen] = useState(false);
    const [removeTarget, setRemoveTarget] = useState<ApprovalPolicy | null>(null);
    const [isRemoving, setIsRemoving] = useState(false);

    const filtered = useMemo(() => {
        const q = query.trim().toLowerCase();
        if (!q) return policies;
        return policies.filter((policy) =>
            [policy.name, policy.documentType, policy.currency].some((v) => v?.toLowerCase().includes(q)));
    }, [policies, query]);

    const openNew = () => {
        setEditorTarget(null);
        setEditorOpen(true);
    };

    const openEdit = (policy: ApprovalPolicy) => {
        setEditorTarget(policy);
        setEditorOpen(true);
    };

    const onSaved = (saved: ApprovalPolicy, isNew: boolean) => {
        setPolicies((prev) => (isNew
            ? [...prev, saved].sort((a, b) => a.name.localeCompare(b.name))
            : prev.map((p) => (p.id === saved.id ? saved : p))));
    };

    const confirmRemove = async () => {
        if (!removeTarget) return;
        setIsRemoving(true);
        try {
            await deleteApprovalPolicy(removeTarget.id);
            setPolicies((prev) => prev.filter((policy) => policy.id !== removeTarget.id));
            toastSuccess(t('deleted'));
            setRemoveTarget(null);
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('deleteFailed'));
        } finally {
            setIsRemoving(false);
        }
    };

    const conditionSummary = (policy: ApprovalPolicy) => {
        const parts: string[] = [];
        if (policy.minTotal != null && policy.currency) {
            parts.push(t('conditionTotal', { amount: formatCurrency(policy.minTotal, policy.currency, locale) }));
        }
        if (policy.minDiscountPercent != null) {
            parts.push(t('conditionDiscount', { percent: policy.minDiscountPercent }));
        }
        return parts.length === 0 ? t('conditionAlways') : parts.join(t('conditionJoin'));
    };

    const actions = (
        <>
            <Button variant="outline" onClick={() => router.push('/library/documents')}>
                <DocumentDuplicateIcon className="size-4" />
                {t('templatesLink')}
            </Button>
            <Button variant="brand" onClick={openNew}>
                <PlusIcon className="size-4" />
                {t('newButton')}
            </Button>
        </>
    );

    const heading = presentation === 'section' ? (
        <SettingsSection
            title={t('title')}
            description={t('sectionDescription')}
            action={<div className="flex flex-wrap items-center gap-2">{actions}</div>}
        />
    ) : (
        <PageHeader title={t('title')} actions={actions} />
    );

    return (
        <>
            <PolicyShell presentation={presentation}>
                <Rise>{heading}</Rise>

                <Rise delay={0.06}>
                    <div className="flex items-center justify-between gap-3">
                        <SectionHeader title={t('sectionPolicies')} />
                        <div className="w-64">
                            <SearchField value={query} onChange={setQuery} onClear={() => setQuery('')}
                                placeholder={t('searchPlaceholder')}
                                searchAria={tf('searchAria')} clearAria={tf('clearSearchAria')} />
                        </div>
                    </div>
                </Rise>

                <Rise delay={0.12}>
                    {filtered.length === 0 ? (
                        query ? (
                            <div className="rounded-2xl border border-border bg-card px-6 py-16 text-center text-sm text-muted-foreground">
                                {t('noMatches')}
                            </div>
                        ) : (
                            <div className="flex flex-col items-center gap-4 rounded-2xl border border-dashed border-border bg-card px-6 py-20 text-center">
                                <div className="flex size-12 items-center justify-center rounded-full bg-muted text-muted-foreground">
                                    <ShieldCheckIcon className="size-6" />
                                </div>
                                <div className="space-y-1">
                                    <p className="text-sm font-medium text-foreground">{t('emptyTitle')}</p>
                                    <p className="mx-auto max-w-sm text-sm text-muted-foreground">{t('emptyBody')}</p>
                                </div>
                                <Button variant="brand" onClick={openNew}>
                                    <PlusIcon className="size-4" />
                                    {t('emptyAction')}
                                </Button>
                            </div>
                        )
                    ) : (
                        <div className="overflow-hidden rounded-2xl border border-border bg-card">
                            <table className="w-full text-sm">
                                <thead>
                                    <tr className="border-b border-border text-left text-xs uppercase tracking-[0.08em] text-muted-foreground">
                                        <th className="px-6 py-3 font-medium">{t('columnName')}</th>
                                        <th className="px-6 py-3 font-medium">{t('columnAppliesTo')}</th>
                                        <th className="px-6 py-3 font-medium">{t('columnCondition')}</th>
                                        <th className="px-6 py-3 font-medium">{t('columnStatus')}</th>
                                        <th className="px-6 py-3" />
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border">
                                    {filtered.map((policy) => (
                                        <tr key={policy.id} className="cursor-pointer transition-colors hover:bg-muted/50"
                                            onClick={() => openEdit(policy)}>
                                            <td className="px-6 py-3">
                                                <div className="font-medium text-foreground">{policy.name}</div>
                                            </td>
                                            <td className="px-6 py-3 text-muted-foreground">
                                                {policy.documentType ? t(TYPE_KEY[policy.documentType]) : t('typeAll')}
                                            </td>
                                            <td className="px-6 py-3 text-muted-foreground">{conditionSummary(policy)}</td>
                                            <td className="px-6 py-3">
                                                <span className={policy.active ? 'text-chart-won' : 'text-muted-foreground'}>
                                                    {policy.active ? t('active') : t('inactive')}
                                                </span>
                                            </td>
                                            <td className="px-6 py-3 text-right">
                                                <DropdownMenu>
                                                    <DropdownMenuTrigger asChild>
                                                        <Button variant="ghost" size="icon-xs" aria-label={t('actions')}
                                                            onClick={(e) => e.stopPropagation()}>
                                                            <EllipsisHorizontalIcon className="size-4" />
                                                        </Button>
                                                    </DropdownMenuTrigger>
                                                    <DropdownMenuContent align="end">
                                                        <DropdownMenuItem onSelect={() => openEdit(policy)}>
                                                            <PencilIcon className="size-4" />{t('edit')}
                                                        </DropdownMenuItem>
                                                        <DropdownMenuSeparator />
                                                        <DropdownMenuItem variant="destructive" onSelect={() => setRemoveTarget(policy)}>
                                                            <TrashIcon className="size-4" />{t('delete')}
                                                        </DropdownMenuItem>
                                                    </DropdownMenuContent>
                                                </DropdownMenu>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                </Rise>
            </PolicyShell>

            <ApprovalPolicyDialog
                open={editorOpen}
                onOpenChange={setEditorOpen}
                policy={editorTarget}
                onSaved={onSaved}
            />

            <DeleteRecordDialog
                open={removeTarget !== null}
                onOpenChange={(next) => { if (!next) setRemoveTarget(null); }}
                selectedIds={removeTarget ? new Set([removeTarget.id]) : new Set()}
                selectedItems={removeTarget ? [removeTarget] : []}
                entityLabel={t('entityLabel')}
                getDisplayName={(policy) => policy.name}
                isDeleting={isRemoving}
                confirmDelete={confirmRemove}
            />
        </>
    );
}
