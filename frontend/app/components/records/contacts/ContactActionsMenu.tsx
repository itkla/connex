'use client';

import { useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { LoaderCircle } from 'lucide-react';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { toastSuccess } from '@/app/lib/toast';
import { EllipsisVerticalIcon, PencilSquareIcon, EyeIcon, PlusIcon, ChatBubbleLeftRightIcon, DocumentTextIcon, CheckCircleIcon, PaperClipIcon } from '@heroicons/react/24/outline';
import { BuildingOffice2Icon, NoSymbolIcon, ArchiveBoxIcon, ArchiveBoxArrowDownIcon, ShareIcon, ShieldExclamationIcon, UserCircleIcon } from '@heroicons/react/24/outline';

import { useAttachmentUploader } from '@/app/components/attachments/useAttachmentUploader';

import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Button } from '@/components/ui/button';
import { IconButton } from '@/components/ui/icon-button';
import { ButtonGroup } from '@/components/ui/button-group';

import ChangeCompanyDialog from '@/app/components/records/contacts/ChangeCompanyDialog';
import ArchiveRecordDialog from '@/app/components/records/ArchiveRecordDialog';
import ShareDialog from '@/app/components/records/ShareDialog';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import {
    RecordActivityComposer,
    RecordTaskComposer,
    type RecordComposerAnchor,
} from '@/app/components/records/RecordComposers';
import NewNoteDialog from '@/app/components/activity/notes/NoteDialog';

import { archiveContact, restoreContact, getActiveWorkspaceMembers, updateContact, updatePersonOwner } from '@/app/lib/api';
import BulkAssignOwnerDialog from '@/app/components/records/BulkAssignOwnerDialog';
import { type WorkspaceMember } from '@/app/lib/types';
import { type Contact, type Deal } from '@/app/lib/types';
import EditContactSheet from '@/app/components/records/contacts/EditContactSheet';
import RestrictionsDialog from '@/app/components/records/contacts/RestrictionsDialog';
import {
    useContactTargetSearch,
    useDealTargetSearch,
} from '@/app/hooks/useRecordTargetSearch';

export default function ContactActionsMenu({
    contact,
    currentUserId,
    dealSeeds = [],
}: {
    contact: Contact;
    currentUserId: number;
    dealSeeds?: Deal[];
}) {
    const router = useRouter();
    const t = useTranslations('ContactsActionsMenu');
    const showApiError = useApiErrorToast('ContactsActionsMenu');
    const { inputRef: attachmentInputRef, uploading: attachmentsUploading, openPicker: openAttachmentPicker, onFilesSelected: onAttachmentFilesSelected } = useAttachmentUploader('person', contact.id);
    const [changeOpen, setChangeOpen] = useState(false);
    const [archiveOpen, setArchiveOpen] = useState(false);
    const [isArchiving, setIsArchiving] = useState(false);
    const [removingCompany, setRemovingCompany] = useState(false);
    const [editOpen, setEditOpen] = useState(false);
    const [activityOpen, setActivityOpen] = useState(false);
    const [taskOpen, setTaskOpen] = useState(false);
    const [noteOpen, setNoteOpen] = useState(false);
    const [shareOpen, setShareOpen] = useState(false);
    const [restrictionsOpen, setRestrictionsOpen] = useState(false);
    const [assignOwnerOpen, setAssignOwnerOpen] = useState(false);
    const [members, setMembers] = useState<WorkspaceMember[]>([]);
    const contactSeeds = useMemo(() => [contact], [contact]);
    const composerAnchor = useMemo<RecordComposerAnchor>(
        () => ({ kind: 'person', person: contact, companyId: contact.companyId ?? contact.company?.id ?? null }),
        [contact],
    );
    const contactSearch = useContactTargetSearch(noteOpen, [contact.id], contactSeeds);
    const dealSearch = useDealTargetSearch(noteOpen, [], dealSeeds);
    useEffect(() => {
        if (!assignOwnerOpen || members.length > 0) return;
        getActiveWorkspaceMembers().then((list) => setMembers(list.filter((member) => member.status === "active"))).catch(() => setMembers([]));
    }, [assignOwnerOpen, members.length]);
    const { activeWorkspaceId } = useWorkspace();
    const owned = contact.workspaceId == null || contact.workspaceId === activeWorkspaceId;
    const handleRemoveCompany = async () => {
        if (!contact.company) return;
        setRemovingCompany(true);
        try {
            await updateContact(contact.id, {
                name: contact.name,
                email: contact.email || undefined,
                phone: contact.phone || undefined,
                title: contact.title || undefined,
                companyId: null,
            });
            toastSuccess(t('toastRemovedFromCompany', { contactName: contact.name, companyName: contact.company.name }));
            router.refresh();
        } catch (err) {
            showApiError(err, 'toastFailedRemoveFromCompany');
        } finally {
            setRemovingCompany(false);
        }
    };

    const archived = contact.archivedAt != null;

    const confirmArchive = async () => {
        setIsArchiving(true);
        try {
            if (archived) {
                await restoreContact(contact.id);
                toastSuccess(t('toastContactRestored'));
                setArchiveOpen(false);
                router.refresh();
            } else {
                await archiveContact(contact.id);
                toastSuccess(t('toastContactArchived'));
                router.push('/records/contacts');
                router.refresh();
            }
        } catch (err) {
            showApiError(err, archived ? 'toastFailedRestore' : 'toastFailedArchive');
        } finally {
            setIsArchiving(false);
        }
    };

    return (
        <>
            <div className="flex flex-row gap-2">
                <ButtonGroup orientation="horizontal">
                    <Button variant="outline" size="toolbar" onClick={() => setEditOpen(true)}>
                        <PencilSquareIcon className="size-4" />
                        {t('edit')}
                    </Button>
                    <Button
                        variant="outline"
                        size="toolbar"
                        onClick={() => router.push(`/overview/map?contactId=${contact.id}`)}
                    >
                        <EyeIcon className="size-4" />
                        {t('viewInMap')}
                    </Button>
                </ButtonGroup>
                <ButtonGroup orientation="horizontal">
                    {/* // add attachments, files, pictures, business cards etc */}
                    <Button
                        variant="outline"
                        size="toolbar"
                        onClick={openAttachmentPicker}
                        disabled={attachmentsUploading}
                    >
                        {attachmentsUploading ? (
                            <LoaderCircle className="size-4 animate-spin" />
                        ) : (
                            <PaperClipIcon className="size-4" />
                        )}
                        {t('add')}
                    </Button>
                    <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                            <Button variant="brand" size="toolbar" menu>
                                <PlusIcon className="size-4" />
                                {t('new')}
                            </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="w-48">
                            <DropdownMenuItem
                                onSelect={() => setActivityOpen(true)}
                            >
                                <ChatBubbleLeftRightIcon className="size-4" />
                                <span>{t('addActivity')}</span>
                            </DropdownMenuItem>
                            <DropdownMenuItem 
                                onSelect={() => setNoteOpen(true)}
                            >
                                <DocumentTextIcon className="size-4" />
                                <span>{t('addNote')}</span>
                            </DropdownMenuItem>
                            <DropdownMenuItem
                                onSelect={() => setTaskOpen(true)}
                            >
                                <CheckCircleIcon className="size-4" />
                                <span>{t('addTask')}</span>
                            </DropdownMenuItem>
                        </DropdownMenuContent>
                    </DropdownMenu>

                    <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                            <IconButton variant="outline" size="icon-toolbar" label={t('moreActions')}>
                                <EllipsisVerticalIcon className="size-4" />
                            </IconButton>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="w-48">
                            <DropdownMenuItem
                                onSelect={(e) => {
                                    e.preventDefault();
                                    setChangeOpen(true);
                                }}
                            >
                                <BuildingOffice2Icon className="size-4" />
                                <span>{contact.company ? t('changeCompany') : t('associateWithCompany')}</span>
                            </DropdownMenuItem>
                            {contact.company ? (
                                <DropdownMenuItem
                                    disabled={removingCompany}
                                    onSelect={(e) => {
                                        e.preventDefault();
                                        handleRemoveCompany();
                                    }}
                                >
                                    <NoSymbolIcon className="size-4" />
                                    <span>{t('removeFromCompanyNamed', { companyName: contact.company.name })}</span>
                                </DropdownMenuItem>
                            ) : null}
                            {owned && (
                                <DropdownMenuItem
                                    onSelect={(e) => {
                                        e.preventDefault();
                                        setAssignOwnerOpen(true);
                                    }}
                                >
                                    <UserCircleIcon className="size-4" />
                                    <span>{t('assignOwner')}</span>
                                </DropdownMenuItem>
                            )}
                            {owned && (
                                <DropdownMenuItem
                                    onSelect={(e) => {
                                        e.preventDefault();
                                        setShareOpen(true);
                                    }}
                                >
                                    <ShareIcon className="size-4" />
                                    <span>{t('share')}</span>
                                </DropdownMenuItem>
                            )}
                            {owned && (
                                <DropdownMenuItem
                                    onSelect={(e) => {
                                        e.preventDefault();
                                        setRestrictionsOpen(true);
                                    }}
                                >
                                    <ShieldExclamationIcon className="size-4" />
                                    <span>{t('restrictions')}</span>
                                </DropdownMenuItem>
                            )}
                            <DropdownMenuSeparator />
                            <DropdownMenuItem
                                onSelect={(e) => {
                                    e.preventDefault();
                                    setArchiveOpen(true);
                                }}
                            >
                                {archived
                                    ? <ArchiveBoxIcon className="size-4" />
                                    : <ArchiveBoxArrowDownIcon className="size-4" />}
                                <span>{t(archived ? 'restore' : 'archive')}</span>
                            </DropdownMenuItem>
                        </DropdownMenuContent>
                    </DropdownMenu>
                </ButtonGroup>

                {/* hidden input to upload attachments */}
                <input
                    ref={attachmentInputRef}
                    type="file"
                    multiple
                    className="hidden"
                    onChange={onAttachmentFilesSelected}
                />

                <EditContactSheet contact={contact} open={editOpen} onOpenChange={setEditOpen} />

                <RestrictionsDialog contact={contact} open={restrictionsOpen} onOpenChange={setRestrictionsOpen} />

                <RecordActivityComposer
                    anchor={composerAnchor}
                    currentUserId={currentUserId}
                    open={activityOpen}
                    onOpenChange={setActivityOpen}
                />
                <RecordTaskComposer
                    anchor={composerAnchor}
                    currentUserId={currentUserId}
                    open={taskOpen}
                    onOpenChange={setTaskOpen}
                />

                <NewNoteDialog
                    persons={contactSearch.contacts}
                    deals={dealSearch.deals}
                    defaultPerson={contact}
                    note={null}
                    currentUserId={currentUserId}
                    open={noteOpen}
                    onOpenChange={setNoteOpen}
                    onPersonQueryChange={contactSearch.onInputValueChange}
                    onDealQueryChange={dealSearch.onInputValueChange}
                    personOptionsLoading={contactSearch.loading}
                    dealOptionsLoading={dealSearch.loading}
                />

                <ChangeCompanyDialog
                    open={changeOpen}
                    onOpenChange={setChangeOpen}
                    contacts={[contact]}
                />
                <ArchiveRecordDialog
                    open={archiveOpen}
                    onOpenChange={setArchiveOpen}
                    mode={archived ? 'restore' : 'archive'}
                    selectedIds={new Set([contact.id])}
                    selectedItems={[contact]}
                    entityLabel={t('entityLabel')}
                    entityLabelPlural={t('entityLabelPlural')}
                    getDisplayName={(c) => c.name}
                    isPending={isArchiving}
                    onConfirm={confirmArchive}
                />

                <ShareDialog
                    type="person"
                    entityId={contact.id}
                    entityName={contact.name}
                    open={shareOpen}
                    onOpenChange={setShareOpen}
                />

                <BulkAssignOwnerDialog
                    open={assignOwnerOpen}
                    onOpenChange={setAssignOwnerOpen}
                    count={1}
                    members={members}
                    messages={{
                        success: () => t('toastOwnerAssigned'),
                        partial: () => t('toastOwnerAssigned'),
                        failure: () => t('toastOwnerFailed'),
                    }}
                    onApply={async (ownerId) => {
                        await updatePersonOwner(contact.id, ownerId);
                        return { succeeded: 1, failed: 0, errors: [] };
                    }}
                    onSuccess={() => router.refresh()}
                />
            </div>
        </>
    );
}
