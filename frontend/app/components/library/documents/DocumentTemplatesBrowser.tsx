'use client';

import { useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { PlusIcon, PencilIcon, TrashIcon, EllipsisHorizontalIcon, DocumentDuplicateIcon } from '@heroicons/react/24/outline';

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
import { deleteDocumentTemplate } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { DocumentTemplate, DocumentType } from '@/app/lib/types';

const TYPE_KEY: Record<DocumentType, string> = {
    quote: 'typeQuote',
    proposal: 'typeProposal',
    order_form: 'typeOrderForm',
    contract: 'typeContract',
};

/**
 * Workspace-scoped commercial-document template admin: searchable list opening the full-page
 * builder. One of the two surfaces `DocumentsLibrary` switches between, so the page title, the
 * create action, and the page shell belong to that parent rather than to this list.
 */
export default function DocumentTemplatesBrowser({ templates: initial }: { templates: DocumentTemplate[] }) {
    const t = useTranslations('DocumentTemplatesBrowser');
    const tf = useTranslations('Filters');
    const router = useRouter();
    const [templates, setTemplates] = useState(initial);
    const [query, setQuery] = useState('');
    const [removeTarget, setRemoveTarget] = useState<DocumentTemplate | null>(null);
    const [isRemoving, setIsRemoving] = useState(false);

    const filtered = useMemo(() => {
        const q = query.trim().toLowerCase();
        if (!q) return templates;
        return templates.filter((tpl) =>
            [tpl.name, tpl.title, tpl.type].some((v) => v?.toLowerCase().includes(q)));
    }, [templates, query]);

    const openNew = () => router.push('/library/documents/new');
    const openEdit = (id: number) => router.push(`/library/documents/${id}`);

    const confirmRemove = async () => {
        if (!removeTarget) return;
        setIsRemoving(true);
        try {
            await deleteDocumentTemplate(removeTarget.id);
            setTemplates((prev) => prev.filter((tpl) => tpl.id !== removeTarget.id));
            toastSuccess(t('deleted'));
            setRemoveTarget(null);
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('deleteFailed'));
        } finally {
            setIsRemoving(false);
        }
    };

    return (
        <>
            <Rise delay={0.06}>
                <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                    <SectionHeader title={t('sectionTemplates')} />
                    <div className="w-full sm:w-64">
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
                                <DocumentDuplicateIcon className="size-6" />
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
                                    <th className="px-4 py-3 font-medium sm:px-6">{t('columnName')}</th>
                                    <th className="hidden px-4 py-3 font-medium sm:px-6 md:table-cell">{t('columnType')}</th>
                                    <th className="hidden px-4 py-3 font-medium sm:table-cell sm:px-6">{t('columnLocale')}</th>
                                    <th className="px-4 py-3 font-medium sm:px-6">{t('columnStatus')}</th>
                                    <th className="px-4 py-3 sm:px-6" />
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {filtered.map((tpl) => (
                                    <tr key={tpl.id} className="cursor-pointer transition-colors hover:bg-muted/50"
                                        onClick={() => openEdit(tpl.id)}>
                                        <td className="px-4 py-3 sm:px-6">
                                            <div className="font-medium text-foreground">{tpl.name}</div>
                                            {tpl.title ? <div className="text-xs text-muted-foreground">{tpl.title}</div> : null}
                                        </td>
                                        <td className="hidden px-4 py-3 text-muted-foreground sm:px-6 md:table-cell">{t(TYPE_KEY[tpl.type])}</td>
                                        <td className="hidden px-4 py-3 uppercase text-muted-foreground sm:table-cell sm:px-6">{tpl.locale}</td>
                                        <td className="px-4 py-3 sm:px-6">
                                            <span className={tpl.active ? 'text-chart-won' : 'text-muted-foreground'}>
                                                {tpl.active ? t('active') : t('inactive')}
                                            </span>
                                        </td>
                                        <td className="px-4 py-3 text-right sm:px-6">
                                            <DropdownMenu>
                                                <DropdownMenuTrigger asChild>
                                                    <Button variant="ghost" size="icon-xs" aria-label={t('actions')}
                                                        onClick={(e) => e.stopPropagation()}>
                                                        <EllipsisHorizontalIcon className="size-4" />
                                                    </Button>
                                                </DropdownMenuTrigger>
                                                <DropdownMenuContent align="end">
                                                    <DropdownMenuItem onSelect={() => openEdit(tpl.id)}>
                                                        <PencilIcon className="size-4" />{t('edit')}
                                                    </DropdownMenuItem>
                                                    <DropdownMenuSeparator />
                                                    <DropdownMenuItem variant="destructive" onSelect={() => setRemoveTarget(tpl)}>
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

            <DeleteRecordDialog
                open={removeTarget !== null}
                onOpenChange={(next) => { if (!next) setRemoveTarget(null); }}
                selectedIds={removeTarget ? new Set([removeTarget.id]) : new Set()}
                selectedItems={removeTarget ? [removeTarget] : []}
                entityLabel={t('entityLabel')}
                getDisplayName={(tpl) => tpl.name}
                isDeleting={isRemoving}
                confirmDelete={confirmRemove}
            />
        </>
    );
}
