'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { LoaderCircle } from 'lucide-react';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { EllipsisVerticalIcon, PencilSquareIcon, EyeIcon, PaperClipIcon, TrashIcon, PlusIcon, UserIcon, UserCircleIcon, BriefcaseIcon, ShareIcon } from '@heroicons/react/24/outline';

import { useAttachmentUploader } from '@/app/components/attachments/useAttachmentUploader';

import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Button } from '@/components/ui/button';
import { ButtonGroup } from '@/components/ui/button-group';

import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import ShareDialog from '@/app/components/records/ShareDialog';
import OwnerAssignDialog from '@/app/components/records/OwnerAssignDialog';
import EditCompanySheet from '@/app/components/records/companies/EditCompanySheet';
import NewContactDialog from '@/app/components/records/contacts/NewContactDialog';
import NewDealDialog from '@/app/components/records/deals/NewDealDialog';

import { createContact, createDeal, deleteCompany, getActiveWorkspaceMembers, getPipelines, getStagesByPipelineId, importBusinessCard, isFieldError, updateCompanyOwner, uploadContactPicture } from '@/app/lib/api';
import { type BusinessCardImportDraft, CreateContactPayload, type Company, type CreateDealPayload, type Pipeline, type Stage, type WorkspaceMember } from '@/app/lib/types';
import { useWorkspace } from '@/app/hooks/useWorkspace';

function emptyContactPayload(companyId: number): CreateContactPayload {
    return {
        name: '',
        email: '',
        phone: '',
        title: '',
        companyId,
    };
}

function emptyDealPayload(companyId: number): CreateDealPayload {
    return {
        name: '',
        value: 0,
        actualValue: 0,
        currency: 'USD',
        pipeline: 0,
        stage: 0,
        company: companyId,
        expectedCloseDate: undefined,
    };
}

export default function CompanyActionsMenu({
    company,
}: {
    company: Company;
}) {
    const router = useRouter();
    const t = useTranslations('CompaniesActionsMenu');
    const { activeWorkspaceId } = useWorkspace();
    const owned = company.workspaceId == null || company.workspaceId === activeWorkspaceId;
    const { inputRef: attachmentInputRef, uploading: attachmentsUploading, openPicker: openAttachmentPicker, onFilesSelected: onAttachmentFilesSelected } = useAttachmentUploader('company', company.id);
    const [deleteOpen, setDeleteOpen] = useState(false);
    const [shareOpen, setShareOpen] = useState(false);
    const [ownerOpen, setOwnerOpen] = useState(false);
    const [members, setMembers] = useState<WorkspaceMember[]>([]);
    const [isDeleting, setIsDeleting] = useState(false);
    const [editOpen, setEditOpen] = useState(false);
    const [newContactDialogOpen, setNewContactDialogOpen] = useState(false);
    const [newDealDialogOpen, setNewDealDialogOpen] = useState(false);
    const [pipelines, setPipelines] = useState<Pipeline[]>([]);
    const [stagesByPipeline, setStagesByPipeline] = useState<Record<number, Stage[]>>({});
    const [newContactPayload, setNewContactPayload] = useState<CreateContactPayload>(
        () => emptyContactPayload(company.id),
    );
    const [imageFile, setImageFile] = useState<File | null>(null);
    const [isCreatingContact, setIsCreatingContact] = useState(false);
    const [contactCreationSucceeded, setContactCreationSucceeded] = useState(false);
    const [isCreatingDeal, setIsCreatingDeal] = useState(false);
    const [dealCreationSucceeded, setDealCreationSucceeded] = useState(false);
    const contactCloseTimerRef = useRef<number | null>(null);
    const contactCloseGenerationRef = useRef(0);

    const invalidatePendingContactClose = useCallback(() => {
        contactCloseGenerationRef.current += 1;
        if (contactCloseTimerRef.current == null) return;
        window.clearTimeout(contactCloseTimerRef.current);
        contactCloseTimerRef.current = null;
    }, []);

    const [newDealPayload, setNewDealPayload] = useState<CreateDealPayload>(
        () => emptyDealPayload(company.id),
    );

    useEffect(() => {
        getPipelines().then(async (ps) => {
            setPipelines(ps);
            const entries = await Promise.all(
                ps.map(async (p) => [p.id, await getStagesByPipelineId(p.id).catch(() => [] as Stage[])] as const),
            );
            setStagesByPipeline(Object.fromEntries(entries));
        }).catch(() => setPipelines([]));
    }, []);

    useEffect(() => { getActiveWorkspaceMembers().then(setMembers).catch(() => setMembers([])); }, []);

    useEffect(
        () => () => invalidatePendingContactClose(),
        [invalidatePendingContactClose],
    );

    const showNewContactDialog = () => {
        invalidatePendingContactClose();
        setNewContactDialogOpen(true);
    };

    const closeNewContactDialog = (open: boolean) => {
        invalidatePendingContactClose();
        setNewContactDialogOpen(open);
        if (!open) {
            setNewContactPayload(emptyContactPayload(company.id));
            setImageFile(null);
            setContactCreationSucceeded(false);
        }
    };

    const closeNewDealDialog = (open: boolean) => {
        setNewDealDialogOpen(open);
        if (!open) {
            setNewDealPayload(emptyDealPayload(company.id));
            setDealCreationSucceeded(false);
        }
    };

    const showNewDealDialog = () => {
        setNewDealPayload(emptyDealPayload(company.id));
        setNewDealDialogOpen(true);
    };

    const confirmDelete = async () => {
        setIsDeleting(true);
        try {
            await deleteCompany(company.id);
            toastSuccess(t('toastCompanyDeleted'));
            router.push('/records/companies');
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastDeleteFailed'));
            setIsDeleting(false);
        }
    };

    const createNewContact = async (businessCard?: BusinessCardImportDraft) => {
        invalidatePendingContactClose();
        const operationGeneration = contactCloseGenerationRef.current;
        const isCurrent = () => contactCloseGenerationRef.current === operationGeneration;
        setContactCreationSucceeded(false);
        setIsCreatingContact(true);
        try {
            const newContact = businessCard
                ? (await importBusinessCard(businessCard)).contact
                : await createContact(newContactPayload);
            if (!isCurrent()) return;
            let avatarUploadFailed = false;
            if (imageFile) {
                try {
                    await uploadContactPicture(newContact.id, imageFile);
                } catch {
                    avatarUploadFailed = true;
                }
                if (!isCurrent()) return;
            }
            if (isCurrent()) setIsCreatingContact(false);
            let finalized = false;
            return {
                avatarUploadFailed,
                avatarUploaded: imageFile != null && !avatarUploadFailed,
                finalize: () => {
                    if (finalized || !isCurrent()) return;
                    finalized = true;
                    toastSuccess(t('toastContactCreated'));
                    setContactCreationSucceeded(true);
                    invalidatePendingContactClose();
                    const closeGeneration = contactCloseGenerationRef.current;
                    contactCloseTimerRef.current = window.setTimeout(() => {
                        if (contactCloseGenerationRef.current !== closeGeneration) return;
                        contactCloseTimerRef.current = null;
                        closeNewContactDialog(false);
                        router.refresh();
                    }, 900);
                },
            };
        } catch (err) {
            if (!isCurrent()) return;
            if (isFieldError(err) || businessCard) {
                throw err;
            }
            toastError(t('toastCreateContactFailed'));
        } finally {
            if (isCurrent()) setIsCreatingContact(false);
        }
    };

    const createNewDeal = async () => {
        setDealCreationSucceeded(false);
        setIsCreatingDeal(true);
        try {
            await createDeal({
                ...newDealPayload,
                name: newDealPayload.name.trim(),
                value: Number.isFinite(newDealPayload.value) ? newDealPayload.value : 0,
                actualValue: Number.isFinite(newDealPayload.actualValue) ? newDealPayload.actualValue : 0,
                currency: newDealPayload.currency.trim() || 'USD',
                pipeline: newDealPayload.pipeline || null,
                stage: newDealPayload.stage || null,
                company: company.id,
                expectedCloseDate: newDealPayload.expectedCloseDate || undefined,
            });
            toastSuccess(t('toastDealCreated'));
            setIsCreatingDeal(false);
            setDealCreationSucceeded(true);
            setTimeout(() => {
                closeNewDealDialog(false);
                router.refresh();
            }, 900);
        } catch (err) {
            if (isFieldError(err)) {
                throw err;
            }
            console.error(err);
            toastError(err instanceof Error ? err.message : t('toastCreateDealFailed'));
        } finally {
            setIsCreatingDeal(false);
        }
    };

    return (
        <div className="flex flex-row gap-2">
            <ButtonGroup orientation="horizontal">
                <Button variant="outline" size="sm" onClick={() => setEditOpen(true)}>
                    <PencilSquareIcon className="size-4" />
                    {t('edit')}
                </Button>
                <Button
                    variant="outline"
                    size="sm"
                    onClick={() => router.push(`/overview/map?companyId=${company.id}`)}
                >
                    <EyeIcon className="size-4" />
                    {t('viewInMap')}
                </Button>
            </ButtonGroup>
            <ButtonGroup orientation="horizontal">
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
                        <DropdownMenuItem onSelect={(e) => {
                            e.preventDefault();
                            showNewContactDialog();
                        }}>
                            <UserIcon className="size-4" />
                            {t('newContact')}
                        </DropdownMenuItem>
                        <DropdownMenuItem onSelect={(e) => {
                            e.preventDefault();
                            showNewDealDialog();
                        }}>
                            <BriefcaseIcon className="size-4" />
                            {t('newDeal')}
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
                                setOwnerOpen(true);
                            }}
                        >
                            <UserCircleIcon className="size-4" />
                            <span>{t('assignOwner')}</span>
                        </DropdownMenuItem>
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

            <input
                ref={attachmentInputRef}
                type="file"
                multiple
                className="hidden"
                onChange={onAttachmentFilesSelected}
            />

            <EditCompanySheet company={company} open={editOpen} onOpenChange={setEditOpen} />

            <ShareDialog
                type="company"
                entityId={company.id}
                entityName={company.name}
                open={shareOpen}
                onOpenChange={setShareOpen}
            />

            <OwnerAssignDialog
                open={ownerOpen}
                onOpenChange={setOwnerOpen}
                initialOwnerId={company.ownerId}
                members={members}
                onApply={(ownerId) => updateCompanyOwner(company.id, ownerId)}
            />

            <DeleteRecordDialog
                open={deleteOpen}
                onOpenChange={setDeleteOpen}
                selectedIds={new Set([company.id])}
                selectedItems={[company]}
                entityLabel={t('entityLabel')}
                getDisplayName={(c) => c.name}
                isDeleting={isDeleting}
                confirmDelete={confirmDelete}
            />

            <NewContactDialog
                newContactDialogOpen={newContactDialogOpen}
                setNewContactDialogOpen={closeNewContactDialog}
                newContactPayload={newContactPayload}
                setNewContactPayload={setNewContactPayload}
                imageFile={imageFile}
                setImageFile={setImageFile}
                selectedCompany={company}
                isCreating={isCreatingContact}
                isSuccess={contactCreationSucceeded}
                createNewContact={createNewContact}
            />

            <NewDealDialog
                open={newDealDialogOpen}
                onOpenChange={closeNewDealDialog}
                payload={newDealPayload}
                setPayload={setNewDealPayload}
                pipelines={pipelines}
                stagesByPipeline={stagesByPipeline}
                isCreating={isCreatingDeal}
                isSuccess={dealCreationSucceeded}
                createNewDeal={createNewDeal}
            />
        </div>
    );
}
