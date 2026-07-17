'use client';

import { useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';
import { PlusIcon, PencilIcon, TrashIcon, EllipsisHorizontalIcon } from '@heroicons/react/24/outline';

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
import DocumentTemplateDialog from '@/app/components/records/document-templates/DocumentTemplateDialog';
import { deleteDocumentTemplate } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { DocumentTemplate, DocumentType } from '@/app/lib/types';

const TYPE_KEY: Record<DocumentType, string> = {
    quote: 'typeQuote',
    proposal: 'typeProposal',
    order_form: 'typeOrderForm',
    contract: 'typeContract',
};

/** Workspace-scoped commercial-document template admin: searchable table with create/edit/delete. */
export default function DocumentTemplatesBrowser({ templates: initial }: { templates: DocumentTemplate[] }) {
    const t = useTranslations('DocumentTemplatesBrowser');
    const tf = useTranslations('Filters');
    const [templates, setTemplates] = useState(initial);
    const [query, setQuery] = useState('');
    const [dialog, setDialog] = useState<{ mode: 'create' | 'edit'; template?: DocumentTemplate } | null>(null);
    const [removeTarget, setRemoveTarget] = useState<DocumentTemplate | null>(null);
    const [isRemoving, setIsRemoving] = useState(false);

    const filtered = useMemo(() => {
        const q = query.trim().toLowerCase();
        if (!q) return templates;
        return templates.filter((tpl) =>
            [tpl.name, tpl.title, tpl.type].some((v) => v?.toLowerCase().includes(q)));
    }, [templates, query]);

    const upsert = (saved: DocumentTemplate) => {
        setTemplates((prev) => prev.some((tpl) => tpl.id === saved.id)
            ? prev.map((tpl) => (tpl.id === saved.id ? saved : tpl))
            : [...prev, saved].sort((a, b) => a.name.localeCompare(b.name)));
    };

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
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-8">
                <Rise>
                    <div className="flex items-center justify-between">
                        <h1 className="text-4xl font-extrabold">{t('title')}</h1>
                        <Button variant="brand" onClick={() => setDialog({ mode: 'create' })}>
                            <PlusIcon className="size-4" />
                            {t('newButton')}
                        </Button>
                    </div>
                </Rise>

                <Rise delay={0.06}>
                    <div className="flex items-center justify-between gap-3">
                        <SectionHeader title={t('sectionTemplates')} />
                        <div className="w-64">
                            <SearchField value={query} onChange={setQuery} onClear={() => setQuery('')}
                                placeholder={t('searchPlaceholder')}
                                searchAria={tf('searchAria')} clearAria={tf('clearSearchAria')} />
                        </div>
                    </div>
                </Rise>

                <Rise delay={0.12}>
                    {filtered.length === 0 ? (
                        <div className="rounded-2xl border border-border bg-card px-6 py-16 text-center text-sm text-muted-foreground">
                            {query ? t('noMatches') : t('empty')}
                        </div>
                    ) : (
                        <div className="overflow-hidden rounded-2xl border border-border bg-card">
                            <table className="w-full text-sm">
                                <thead>
                                    <tr className="border-b border-border text-left text-xs uppercase tracking-[0.08em] text-muted-foreground">
                                        <th className="px-6 py-3 font-medium">{t('columnName')}</th>
                                        <th className="px-6 py-3 font-medium">{t('columnType')}</th>
                                        <th className="px-6 py-3 font-medium">{t('columnLocale')}</th>
                                        <th className="px-6 py-3 font-medium">{t('columnStatus')}</th>
                                        <th className="px-6 py-3" />
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border">
                                    {filtered.map((tpl) => (
                                        <tr key={tpl.id} className="transition-colors hover:bg-muted/50">
                                            <td className="px-6 py-3">
                                                <div className="font-medium text-foreground">{tpl.name}</div>
                                                {tpl.title ? <div className="text-xs text-muted-foreground">{tpl.title}</div> : null}
                                            </td>
                                            <td className="px-6 py-3 text-muted-foreground">{t(TYPE_KEY[tpl.type])}</td>
                                            <td className="px-6 py-3 uppercase text-muted-foreground">{tpl.locale}</td>
                                            <td className="px-6 py-3">
                                                <span className={tpl.active ? 'text-chart-won' : 'text-muted-foreground'}>
                                                    {tpl.active ? t('active') : t('inactive')}
                                                </span>
                                            </td>
                                            <td className="px-6 py-3 text-right">
                                                <DropdownMenu>
                                                    <DropdownMenuTrigger asChild>
                                                        <Button variant="ghost" size="icon-xs" aria-label={t('actions')}>
                                                            <EllipsisHorizontalIcon className="size-4" />
                                                        </Button>
                                                    </DropdownMenuTrigger>
                                                    <DropdownMenuContent align="end">
                                                        <DropdownMenuItem onSelect={() => setDialog({ mode: 'edit', template: tpl })}>
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
            </div>

            {dialog && (
                <DocumentTemplateDialog
                    key={dialog.mode === 'edit' ? `edit-${dialog.template?.id}` : 'create'}
                    open
                    onOpenChange={(next) => { if (!next) setDialog(null); }}
                    mode={dialog.mode}
                    template={dialog.template}
                    onSaved={upsert}
                />
            )}

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
        </div>
    );
}
