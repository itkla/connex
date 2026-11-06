'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { UsersIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import RecordSelect from '@/app/components/records/RecordSelect';
import ContactAvatar from '@/app/components/records/contacts/ContactAvatar';
import WarmthPill from '@/app/components/records/WarmthPill';
import { QuickEditSheetShell } from '@/app/components/records/quick-edit/QuickEditSheetShell';
import { useContactTargetSearch } from '@/app/hooks/useRecordTargetSearch';
import { usePermission } from '@/app/hooks/usePermissions';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { addDealPerson, removeDealPerson, updateDealPersonRole } from '@/app/lib/api';
import { renderedWorkspaceId, workspacePinnedRequest } from '@/app/lib/renderedWorkspace';
import { toastSuccess } from '@/app/lib/toast';
import type { Contact, RelationshipTemperature } from '@/app/lib/types';

type Stakeholder = { person: Contact; role: string | null };

/** Direct, permission-gated stakeholder membership and role editing on a deal. */
export default function DealStakeholders({ dealId, people, temperatures }: {
    dealId: number;
    people: Stakeholder[];
    temperatures: RelationshipTemperature[] | null;
}) {
    const t = useTranslations('DealStakeholders');
    const router = useRouter();
    const canEdit = usePermission('DEAL_UPDATE');
    const showApiError = useApiErrorToast('DealStakeholders');
    const [editor, setEditor] = useState<{ personId: string; role: string; editing: boolean; workspaceId: number | null } | null>(null);
    const [busy, setBusy] = useState(false);
    const [contactRequired, setContactRequired] = useState(false);
    const search = useContactTargetSearch(editor !== null, [Number(editor?.personId) || null]);
    const choices = search.contacts.filter((person) => editor?.editing || !people.some((member) => member.person.id === person.id));

    const save = async () => {
        if (!editor || busy) return;
        if (!editor.personId) {
            setContactRequired(true);
            return;
        }
        setBusy(true);
        try {
            const write = editor.editing ? updateDealPersonRole : addDealPerson;
            await write(dealId, Number(editor.personId), editor.role.trim(), workspacePinnedRequest(editor.workspaceId));
            setEditor(null);
            toastSuccess(t('saved'));
            router.refresh();
        } catch (error) {
            showApiError(error, 'saveFailed');
        } finally {
            setBusy(false);
        }
    };

    const remove = async (personId: number) => {
        if (busy) return;
        setBusy(true);
        try {
            await removeDealPerson(dealId, personId);
            toastSuccess(t('removed'));
            router.refresh();
        } catch (error) {
            showApiError(error, 'removeFailed');
        } finally {
            setBusy(false);
        }
    };

    return (
        <section id="deal-stakeholders" className="scroll-mt-24 space-y-3" aria-label={t('title')}>
            <div className="flex items-center justify-between gap-2">
                <h2 className="text-sm font-semibold">{t('title')}</h2>
                {canEdit ? <Button variant="outline" size="inline" disabled={busy} onClick={() => { setContactRequired(false); setEditor({ personId: '', role: '', editing: false, workspaceId: renderedWorkspaceId() }); }}>{t('add')}</Button> : null}
            </div>
            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                {people.length === 0 ? <p className="p-4 text-sm text-muted-foreground">{t('empty')}</p> : (
                    <ul className="divide-y divide-border">
                        {people.map(({ person, role }) => (
                            <li key={person.id} className="p-4">
                                <Link href={`/records/contacts/${person.id}`} className="flex items-center gap-3 rounded-md focus-visible:outline-2 focus-visible:outline-ring">
                                    <ContactAvatar contact={person} type="medium" />
                                    <div className="min-w-0 flex-1">
                                        <p className="truncate text-sm font-medium">{person.name}</p>
                                        {person.title ? <p className="truncate text-xs text-muted-foreground">{person.title}</p> : null}
                                        {role ? <p className="break-words text-xs text-muted-foreground">{role}</p> : null}
                                        <div className="mt-1">{temperatures ? <WarmthPill temp={temperatures.find((item) => item.id === person.id)} /> : <span className="text-xs text-muted-foreground">{t('warmthUnavailable')}</span>}</div>
                                    </div>
                                </Link>
                                {canEdit ? (
                                    <div className="mt-2 flex flex-wrap justify-end gap-2">
                                        <Button variant="ghost" size="inline" disabled={busy} aria-label={t('editRoleFor', { name: person.name })} onClick={() => { setContactRequired(false); setEditor({ personId: String(person.id), role: role ?? '', editing: true, workspaceId: renderedWorkspaceId() }); }}>{t('editRole')}</Button>
                                        <Button variant="ghost" size="inline" disabled={busy} aria-label={t('removeFor', { name: person.name })} onClick={() => void remove(person.id)}>{t('remove')}</Button>
                                    </div>
                                ) : null}
                            </li>
                        ))}
                    </ul>
                )}
            </div>
            <QuickEditSheetShell
                open={editor !== null}
                onOpenChange={(open) => { if (!open) setEditor(null); }}
                icon={<UsersIcon />}
                title={t(editor?.editing ? 'editRole' : 'add')}
                description={t('description')}
                count={1}
                isSaving={busy}
                interactionPending={busy}
                onSave={() => void save()}
                saveLabel={t('save')}
                cancelLabel={t('cancel')}
                dirtySnapshot={editor ? [editor.personId, editor.role] : null}
            >
                <div className="space-y-4">
                    <div className="space-y-2" role="group" aria-describedby={contactRequired ? 'stakeholder-contact-error' : undefined}>
                        <Label htmlFor="stakeholder-contact">{t('contact')}</Label>
                        <RecordSelect
                            id="stakeholder-contact"
                            value={editor?.personId ?? ''}
                            options={choices.map((person) => ({ id: person.id, label: person.name, imageUrl: person.imageUrl }))}
                            onValueChange={(personId) => { setContactRequired(false); setEditor((current) => current ? { ...current, personId } : null); }}
                            onInputValueChange={search.onInputValueChange}
                            serverSearch={search.serverSearch}
                            placeholder={t('chooseContact')}
                            disabled={busy || editor?.editing}
                        />
                        {contactRequired ? <p id="stakeholder-contact-error" role="alert" className="text-sm text-destructive">{t('contactRequired')}</p> : null}
                        {search.error ? <p role="alert" className="text-sm text-destructive">{t('searchFailed')}</p> : null}
                    </div>
                    <div className="space-y-2">
                        <Label htmlFor="stakeholder-role">{t('role')}</Label>
                        <Input maxLength={64} id="stakeholder-role" value={editor?.role ?? ''} disabled={busy} onChange={(event) => setEditor((current) => current ? { ...current, role: event.target.value } : null)} placeholder={t('rolePlaceholder')} />
                    </div>
                </div>
            </QuickEditSheetShell>
        </section>
    );
}
