'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Drawer, DrawerContent, DrawerDescription, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';
import { Skeleton } from '@/components/ui/skeleton';
import { RecordListState } from '@/app/components/records/RecordListState';
import RecordSelect from '@/app/components/records/RecordSelect';
import { useContactTargetSearch, useDealTargetSearch } from '@/app/hooks/useRecordTargetSearch';
import { useCompanySearch } from '@/app/hooks/useCompanySearch';
import { usePermission } from '@/app/hooks/usePermissions';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { addContactTag, removeContactTag, addCompanyTag, removeCompanyTag, addTagToDeal, removeTagFromDeal, getPeopleForTag, getCompaniesForTag, getDealsForTag, workspacePinnedRequest } from '@/app/lib/api';
import { renderedWorkspaceId } from '@/app/lib/renderedWorkspace';
import { toastSuccess } from '@/app/lib/toast';
import type { Tag } from '@/app/lib/types';
import type { ServerPickerSearch } from '@/lib/server-picker-search';

type Member = { id: number; name: string };
type Members = { contacts: Member[]; companies: Member[]; deals: Member[] };
type Kind = keyof Members;

/** Inspects tag membership and applies reversible membership changes through record APIs. */
export default function TagMembersSheet({ tag, onClose }: { tag: Tag; onClose: () => void }) {
    const t = useTranslations('TagMembers');
    const showApiError = useApiErrorToast('TagMembers');
    const [members, setMembers] = useState<Members | null>(null);
    const [error, setError] = useState<Error | null>(null);
    const [loading, setLoading] = useState(true);
    const [revision, setRevision] = useState(0);
    const [busy, setBusy] = useState(false);
    const contacts = useContactTargetSearch(true);
    const companies = useCompanySearch(true);
    const deals = useDealTargetSearch(true);
    const canEditContacts = usePermission('PERSON_UPDATE');
    const canEditCompanies = usePermission('COMPANY_UPDATE');
    const canEditDeals = usePermission('DEAL_UPDATE');

    useEffect(() => {
        const controller = new AbortController();
        const init = { signal: controller.signal };
        Promise.all([getPeopleForTag(tag.id, init), getCompaniesForTag(tag.id, init), getDealsForTag(tag.id, init)])
            .then(([contacts, companies, deals]) => {
                if (controller.signal.aborted) return;
                setMembers({ contacts, companies, deals });
                setError(null);
            })
            .catch((error: unknown) => {
                if (!controller.signal.aborted) {
                    setMembers(null);
                    setError(error instanceof Error ? error : new Error());
                }
            })
            .finally(() => { if (!controller.signal.aborted) setLoading(false); });
        return () => controller.abort();
    }, [tag.id, revision]);

    const change = async (kind: Kind, id: number, add: boolean) => {
        if (busy) return;
        const origin = workspacePinnedRequest(renderedWorkspaceId());
        setBusy(true);
        try {
            const write = kind === 'contacts' ? (add ? addContactTag : removeContactTag)
                : kind === 'companies' ? (add ? addCompanyTag : removeCompanyTag)
                    : (add ? addTagToDeal : removeTagFromDeal);
            await write(id, tag.id, origin);
            toastSuccess(t(add ? 'added' : 'removed'));
            setLoading(true);
            setRevision((value) => value + 1);
        } catch (error) {
            showApiError(error, 'changeFailed');
        } finally {
            setBusy(false);
        }
    };

    return (
        <Drawer open onOpenChange={(open) => { if (!open && !busy) onClose(); }} swipeDirection="right">
            <DrawerContent className="flex w-full flex-col sm:max-w-lg">
                <DrawerHeader className="pr-12">
                    <DrawerTitle>{t('title', { name: tag.name })}</DrawerTitle>
                    <DrawerDescription>{t('description')}</DrawerDescription>
                </DrawerHeader>
                <div className="min-h-0 space-y-6 overflow-y-auto px-4 pb-6">
                    <Button variant="outline" asChild><Link href={`/records/contacts?tag=${tag.id}`}>{t('viewContacts')}</Link></Button>
                    <RecordListState error={error} loading={loading} hasRecords={false} onRetry={() => { setLoading(true); setRevision((value) => value + 1); }}>
                        {loading ? <Skeleton className="h-40 w-full" /> : members ? (
                            <>
                                <MembershipSection kind="contacts" members={members.contacts} choices={contacts.contacts} search={contacts.serverSearch} onSearch={contacts.onInputValueChange} searchFailed={!!contacts.error} canEdit={canEditContacts} busy={busy} onChange={change} />
                                <MembershipSection kind="companies" members={members.companies} choices={companies.companies} search={companies.serverSearch} onSearch={companies.onInputValueChange} searchFailed={!!companies.error} canEdit={canEditCompanies} busy={busy} onChange={change} />
                                <MembershipSection kind="deals" members={members.deals} choices={deals.deals} search={deals.serverSearch} onSearch={deals.onInputValueChange} searchFailed={!!deals.error} canEdit={canEditDeals} busy={busy} onChange={change} />
                            </>
                        ) : null}
                    </RecordListState>
                </div>
            </DrawerContent>
        </Drawer>
    );
}

function MembershipSection({ kind, members, choices, search, onSearch, searchFailed, canEdit, busy, onChange }: {
    kind: Kind;
    members: Member[];
    choices: Member[];
    search: ServerPickerSearch;
    onSearch: (value: string) => void;
    searchFailed: boolean;
    canEdit: boolean;
    busy: boolean;
    onChange: (kind: Kind, id: number, add: boolean) => Promise<void>;
}) {
    const t = useTranslations('TagMembers');
    return (
        <section aria-label={t(kind)} className="space-y-3">
            <h2 className="text-sm font-semibold">{t(kind)} <span className="text-muted-foreground">({members.length})</span></h2>
            {canEdit ? (
                <div>
                    <label htmlFor={`tag-member-${kind}`} className="mb-2 block text-sm">{t(`add.${kind}`)}</label>
                    <RecordSelect id={`tag-member-${kind}`} value="" options={choices.filter((choice) => !members.some((member) => member.id === choice.id)).map((choice) => ({ id: choice.id, label: choice.name }))} onValueChange={(id) => void onChange(kind, Number(id), true)} onInputValueChange={onSearch} serverSearch={search} placeholder={t('search')} disabled={busy} />
                    {searchFailed ? <p role="alert" className="text-sm text-destructive">{t('searchFailed')}</p> : null}
                </div>
            ) : null}
            {members.length === 0 ? <p className="text-sm text-muted-foreground">{t('empty')}</p> : (
                <ul className="divide-y divide-border">
                    {members.map((member) => (
                        <li key={member.id} className="flex items-center gap-3 py-2">
                            <Link className="min-w-0 flex-1 truncate text-sm text-brand-text underline-offset-4 hover:underline" href={`/records/${kind}/${member.id}`}>{member.name}</Link>
                            {canEdit ? <Button variant="ghost" size="inline" disabled={busy} aria-label={t('removeFor', { name: member.name })} onClick={() => void onChange(kind, member.id, false)}>{t('remove')}</Button> : null}
                        </li>
                    ))}
                </ul>
            )}
        </section>
    );
}
