'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { EllipsisVerticalIcon, PencilSquareIcon, EyeIcon, PaperClipIcon, TrashIcon, PlusIcon, UserIcon, BriefcaseIcon, BoltIcon } from '@heroicons/react/24/outline';

import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Button } from '@/components/ui/button';
import { ButtonGroup } from '@/components/ui/button-group';

import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import EditCompanySheet from '@/app/components/records/companies/EditCompanySheet';
import NewContactDialog from '@/app/components/records/contacts/NewContactDialog';

import { deleteCompany, createContact, updateContact } from '@/app/lib/api';
import { CreateContactPayload, type Company } from '@/app/lib/types';
import { uploadContactPicture } from '@/app/lib/utils';

export default function CompanyActionsMenu({
    company,
}: {
    company: Company;
}) {
    const router = useRouter();
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [isDeleting, setIsDeleting] = useState(false);
    const [editOpen, setEditOpen] = useState(false);
    const [newContactDialogOpen, setNewContactDialogOpen] = useState(false);
    const [newContactPayload, setNewContactPayload] = useState<CreateContactPayload>({
        name: '',
        email: '',
        phone: '',
        title: '',
        companyId: company.id,
    });
    const [imageFile, setImageFile] = useState<File | null>(null);
    const [isCreating, setIsCreating] = useState(false);
    const showNewContactDialog = () => {
        setNewContactDialogOpen(true);
    };

    const confirmDelete = async () => {
        setIsDeleting(true);
        try {
            await deleteCompany(company.id);
            toast.success('Company deleted', {
                style: { backgroundColor: 'var(--color-brand)', color: 'white' },
            });
            router.push('/records/companies');
            router.refresh();
        } catch (err) {
            toast.error(err instanceof Error ? err.message : 'Failed to delete', {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
            setIsDeleting(false);
        }
    };

    const createNewContact = async () => {
        setIsCreating(true);
        try {
            // console.log('newContactPayload', newContactPayload);
            const newContact = await createContact(newContactPayload);
            if (imageFile) {
                const imageUrl = await uploadContactPicture(newContact.id, imageFile);
                await updateContact(newContact.id, { ...newContactPayload, imageUrl });
            }
            toast.success('Contact created', {
                style: { backgroundColor: 'var(--color-brand)', color: 'white' },
            });
            setNewContactDialogOpen(false);
            router.refresh();
        } catch (err) {
            console.error(err);
            toast.error('Failed to create contact', {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
        } finally {
            setIsCreating(false);
        }
    };
    return (
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
                        <DropdownMenuItem onSelect={(e) => {
                            e.preventDefault();
                            // router.push(`/records/contacts/new?companyId=${company.id}`);
                            showNewContactDialog();
                        }}>
                            <UserIcon className="size-4" />
                            New contact
                        </DropdownMenuItem>
                        <DropdownMenuItem>
                            <BriefcaseIcon className="size-4" />
                            New deal
                        </DropdownMenuItem>
                        <DropdownMenuItem>
                            <BoltIcon className="size-4" />
                            New pipeline
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

            <EditCompanySheet company={company} open={editOpen} onOpenChange={setEditOpen} />

            <DeleteRecordDialog
                open={deleteOpen}
                onOpenChange={setDeleteOpen}
                selectedIds={new Set([company.id])}
                selectedItems={[company]}
                entityLabel="company"
                getDisplayName={(c) => c.name}
                isDeleting={isDeleting}
                confirmDelete={confirmDelete}
            />

            <NewContactDialog
                newContactDialogOpen={newContactDialogOpen}
                setNewContactDialogOpen={setNewContactDialogOpen}
                newContactPayload={newContactPayload}
                setNewContactPayload={setNewContactPayload}
                imageFile={imageFile}
                setImageFile={setImageFile}
                companies={[company]}
                selectedCompany={company}
                isCreating={isCreating}
                createNewContact={createNewContact}
            />
        </div>
    );
}
