'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { EllipsisVerticalIcon, PencilSquareIcon, EyeIcon, PlusIcon, ChatBubbleLeftRightIcon, DocumentTextIcon, CheckCircleIcon, PaperClipIcon } from '@heroicons/react/24/outline';
import { BuildingOffice2Icon, NoSymbolIcon, TrashIcon } from '@heroicons/react/24/outline';

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

import { deleteContact, updateContact } from '@/app/lib/api';
import { type Company, type Contact } from '@/app/lib/types';
import EditContactSheet from '@/app/components/records/contacts/EditContactSheet';

export default function ContactActionsMenu({
    contact,
    companies,
    currentUserId,
}: {
    contact: Contact;
    companies: Company[];
    currentUserId: number;
}) {
    const router = useRouter();
    const [changeOpen, setChangeOpen] = useState(false);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [isDeleting, setIsDeleting] = useState(false);
    const [removingCompany, setRemovingCompany] = useState(false);
    const [editOpen, setEditOpen] = useState(false);
    const [activityOpen, setActivityOpen] = useState(false);
    const [taskOpen, setTaskOpen] = useState(false);

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
            toast.success(`Removed ${contact.name} from ${contact.company.name}`, {
                style: { backgroundColor: 'var(--color-brand)', color: 'white' },
            });
            router.refresh();
        } catch (err) {
            toast.error(err instanceof Error ? err.message : 'Failed to remove from company', {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
        } finally {
            setRemovingCompany(false);
        }
    };

    const confirmDelete = async () => {
        setIsDeleting(true);
        try {
            await deleteContact(contact.id);
            toast.success('Contact deleted', {
                style: { backgroundColor: 'var(--color-brand)', color: 'white' },
            });
            router.push('/records/contacts');
            router.refresh();
        } catch (err) {
            toast.error(err instanceof Error ? err.message : 'Failed to delete', {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
            setIsDeleting(false);
        }
    };

    return (
        <>
            <div className="flex flex-row gap-2">
                <ButtonGroup orientation="horizontal">
                    <Button variant="outline" size="sm" onClick={() => setEditOpen(true)}>
                        <PencilSquareIcon className="size-4" />
                        Edit
                    </Button>
                    <Button variant="outline" size="sm">
                        <EyeIcon className="size-4" />
                        View in map
                    </Button>
                </ButtonGroup>
                <ButtonGroup orientation="horizontal">
                    {/* // add attachments, files, pictures, business cards etc */}
                    <Button variant="outline" size="sm">
                        <PaperClipIcon className="size-4" />
                        Add
                    </Button>
                    <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                            <Button variant="outline" size="sm">
                                <PlusIcon className="size-4" />
                                New
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
                                <span>Add activity</span>
                            </DropdownMenuItem>
                            <DropdownMenuItem disabled>
                                <DocumentTextIcon className="size-4" />
                                <span>Add note</span>
                            </DropdownMenuItem>
                            <DropdownMenuItem
                                onSelect={(e) => {
                                    e.preventDefault();
                                    setTaskOpen(true);
                                }}
                            >
                                <CheckCircleIcon className="size-4" />
                                <span>Add task</span>
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
                                <span>{contact.company ? 'Change company' : 'Associate with company'}</span>
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
                                    <span>Remove from {contact.company.name}</span>
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
                                <span>Delete</span>
                            </DropdownMenuItem>
                        </DropdownMenuContent>
                    </DropdownMenu>
                </ButtonGroup>

                <EditContactSheet contact={contact} open={editOpen} onOpenChange={setEditOpen} />

                <NewActivityDialog
                    contactId={contact.id}
                    contactName={contact.name}
                    currentUserId={currentUserId}
                    open={activityOpen}
                    onOpenChange={setActivityOpen}
                />
                <NewTaskDialog
                    contactId={contact.id}
                    contactName={contact.name}
                    currentUserId={currentUserId}
                    open={taskOpen}
                    onOpenChange={setTaskOpen}
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