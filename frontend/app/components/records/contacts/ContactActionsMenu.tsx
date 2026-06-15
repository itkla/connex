'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { LoaderCircle } from 'lucide-react';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { EllipsisVerticalIcon, PencilSquareIcon, EyeIcon, PlusIcon, ChatBubbleLeftRightIcon, DocumentTextIcon, CheckCircleIcon, PaperClipIcon } from '@heroicons/react/24/outline';
import { BuildingOffice2Icon, NoSymbolIcon, TrashIcon } from '@heroicons/react/24/outline';

import { useAttachmentUploader } from '@/app/components/attachments/useAttachmentUploader';

import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Button } from '@/components/ui/button';
import { ButtonGroup } from '@/components/ui/button-group';

import ChangeCompanyDialog from '@/app/components/records/contacts/ChangeCompanyDialog';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import NewActivityDialog from '@/app/components/records/contacts/NewActivityDialog';
import NewTaskDialog from '@/app/components/records/contacts/NewTaskDialog';
import NewNoteDialog from '@/app/components/activity/notes/NoteDialog';

import { deleteContact, updateContact } from '@/app/lib/api';
import { type Company, type Contact, type Deal } from '@/app/lib/types';
import EditContactSheet from '@/app/components/records/contacts/EditContactSheet';

export default function ContactActionsMenu({
    contact,
    companies,
    currentUserId,
    persons = [],
    deals = [],
}: {
    contact: Contact;
    companies: Company[];
    currentUserId: number;
    persons?: Contact[];
    deals?: Deal[];
}) {
    const router = useRouter();
    const t = useTranslations('ContactsActionsMenu');
    const { inputRef: attachmentInputRef, uploading: attachmentsUploading, openPicker: openAttachmentPicker, onFilesSelected: onAttachmentFilesSelected } = useAttachmentUploader('person', contact.id);
    const [changeOpen, setChangeOpen] = useState(false);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [isDeleting, setIsDeleting] = useState(false);
    const [removingCompany, setRemovingCompany] = useState(false);
    const [editOpen, setEditOpen] = useState(false);
    const [activityOpen, setActivityOpen] = useState(false);
    const [taskOpen, setTaskOpen] = useState(false);
    const [noteOpen, setNoteOpen] = useState(false);
    const handleRemoveCompany = async () => {
        if (!contact.company) return;
        setRemovingCompany(true);
        try {
            await updateContact(contact.id, {
                name: contact.name,
                email: contact.email || undefined,
                phone: contact.phone || undefined,
                title: contact.title || undefined,
                imageUrl: contact.imageUrl || undefined,
                companyId: null,
            });
            toastSuccess(t('toastRemovedFromCompany', { contactName: contact.name, companyName: contact.company.name }));
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailedRemoveFromCompany'));
        } finally {
            setRemovingCompany(false);
        }
    };

    const confirmDelete = async () => {
        setIsDeleting(true);
        try {
            await deleteContact(contact.id);
            toastSuccess(t('toastContactDeleted'));
            router.push('/records/contacts');
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailedDelete'));
            setIsDeleting(false);
        }
    };

    return (
        <>
            <div className="flex flex-row gap-2">
                <ButtonGroup orientation="horizontal">
                    <Button variant="outline" size="sm" onClick={() => setEditOpen(true)}>
                        <PencilSquareIcon className="size-4" />
                        {t('edit')}
                    </Button>
                    <Button
                        variant="outline"
                        size="sm"
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
                        size="sm"
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
                            <Button variant="outline" size="sm">
                                <PlusIcon className="size-4" />
                                {t('new')}
                            </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="w-48">
                            <DropdownMenuItem
                                onSelect={(e) => {
                                    e.preventDefault();
                                    setActivityOpen(true);
                                }}
                            >
                                <ChatBubbleLeftRightIcon className="size-4" />
                                <span>{t('addActivity')}</span>
                            </DropdownMenuItem>
                            <DropdownMenuItem 
                                onSelect={(e) => {
                                    e.preventDefault();
                                    setNoteOpen(true);
                                }}
                            >
                                <DocumentTextIcon className="size-4" />
                                <span>{t('addNote')}</span>
                            </DropdownMenuItem>
                            <DropdownMenuItem
                                onSelect={(e) => {
                                    e.preventDefault();
                                    setTaskOpen(true);
                                }}
                            >
                                <CheckCircleIcon className="size-4" />
                                <span>{t('addTask')}</span>
                            </DropdownMenuItem>
                        </DropdownMenuContent>
                    </DropdownMenu>

                    <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                            <Button variant="outline" size="sm">
                                <EllipsisVerticalIcon className="size-4" />
                            </Button>
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
                            <DropdownMenuSeparator />
                            <DropdownMenuItem
                                variant="destructive"
                                onSelect={(e) => {
                                    e.preventDefault();
                                    setDeleteOpen(true);
                                }}
                            >
                                <TrashIcon className="size-4" />
                                <span>{t('delete')}</span>
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

                <NewActivityDialog
                    contactId={contact.id}
                    contactName={contact.name}
                    companyId={contact.companyId ?? contact.company?.id}
                    currentUserId={currentUserId}
                    open={activityOpen}
                    onOpenChange={setActivityOpen}
                />
                <NewTaskDialog
                    contactId={contact.id}
                    contactName={contact.name}
                    companyId={contact.companyId ?? contact.company?.id}
                    currentUserId={currentUserId}
                    open={taskOpen}
                    onOpenChange={setTaskOpen}
                />

                <NewNoteDialog
                    persons={persons.length > 0 ? persons : [contact]}
                    deals={deals}
                    defaultPerson={contact}
                    note={null}
                    currentUserId={currentUserId}
                    open={noteOpen}
                    onOpenChange={setNoteOpen}
                />

                <ChangeCompanyDialog
                    open={changeOpen}
                    onOpenChange={setChangeOpen}
                    contacts={[contact]}
                    companies={companies}
                />
                <DeleteRecordDialog
                    open={deleteOpen}
                    onOpenChange={setDeleteOpen}
                    selectedIds={new Set([contact.id])}
                    selectedItems={[contact]}
                    entityLabel="contact"
                    getDisplayName={(c) => c.name}
                    isDeleting={isDeleting}
                    confirmDelete={confirmDelete}
                />
            </div>
        </>
    );
}