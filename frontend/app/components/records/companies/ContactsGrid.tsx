'use client';

import { type Contact, type Company, type Tag } from "@/app/lib/types";
import ContactCard from "@/app/components/records/contacts/ContactCard";
import { EmptyState } from "@/app/components/EmptyState";
import { Button } from "@/components/ui/button";
import { PlusIcon, UsersIcon } from "@heroicons/react/24/outline";

import NewContactDialog from "@/app/components/records/contacts/NewContactDialog";
import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { type BusinessCardImportDraft, CreateContactPayload } from "@/app/lib/types";
import { createContact, importBusinessCard, isFieldError, uploadContactPicture } from "@/app/lib/api";
import { toast } from "sonner";
import { useTranslations } from "next-intl";

function emptyContactPayload(companyId: number): CreateContactPayload {
    return {
        name: '',
        email: '',
        phone: '',
        title: '',
        companyId,
    };
}

export default function ContactsGrid({ contacts, company, allTags }: { contacts: Contact[], company: Company, allTags: Tag[] }) {
    const router = useRouter();
    const t = useTranslations('CompaniesContactsGrid');
    const [newContactDialogOpen, setNewContactDialogOpen] = useState(false);
    const [newContactPayload, setNewContactPayload] = useState<CreateContactPayload>(
        () => emptyContactPayload(company.id),
    );
    const [imageFile, setImageFile] = useState<File | null>(null);
    const [isCreating, setIsCreating] = useState(false);
    const [creationSucceeded, setCreationSucceeded] = useState(false);
    const closeTimerRef = useRef<number | null>(null);
    const closeGenerationRef = useRef(0);

    const invalidatePendingClose = useCallback(() => {
        closeGenerationRef.current += 1;
        if (closeTimerRef.current == null) return;
        window.clearTimeout(closeTimerRef.current);
        closeTimerRef.current = null;
    }, []);

    useEffect(() => () => invalidatePendingClose(), [invalidatePendingClose]);

    const openNewContactDialog = () => {
        invalidatePendingClose();
        setNewContactDialogOpen(true);
    };

    const closeNewContactDialog = (open: boolean) => {
        invalidatePendingClose();
        setNewContactDialogOpen(open);
        if (!open) {
            setNewContactPayload(emptyContactPayload(company.id));
            setImageFile(null);
            setCreationSucceeded(false);
        }
    };
    return (
        <>
            <div className="mt-6 mb-3 flex h-8 items-center justify-between">
                <h2 className="px-6 text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                    {t('contacts')}
                </h2>
                <button
                    type="button"
                    aria-label={t('newContact')}
                    onClick={openNewContactDialog}
                >
                    <PlusIcon className="size-4 text-muted-foreground hover:text-foreground transition-colors duration-300 cursor-pointer" />
                </button>
            </div>
            {contacts.length === 0 ? (
                <div className="overflow-hidden rounded-2xl bg-muted ring-1 ring-border">
                    <EmptyState
                        variant="inline"
                        icon={UsersIcon}
                        title={t('noContacts')}
                        body={t('noContactsBody')}
                        action={
                            <Button variant="brand" onClick={openNewContactDialog}>
                                <PlusIcon strokeWidth={2.5} className="size-4" />
                                {t('newContact')}
                            </Button>
                        }
                    />
                </div>
            ) : (
                <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                    {contacts.map((contact) => (
                        <ContactCard
                            key={contact.id}
                            id={contact.id}
                            name={contact.name}
                            title={contact.title}
                            imageUrl={contact.imageUrl}
                            company={contact.company?.name}
                            companyId={contact.companyId ?? contact.company?.id}
                            email={contact.email}
                            tags={contact.tagIds?.map((tagId) => allTags.find((t) => t.id === tagId))?.filter((t): t is Tag => t !== undefined) ?? []}
                            phone={contact.phone}
                        />
                    ))}
                </ul>
            )}

            <NewContactDialog
                newContactDialogOpen={newContactDialogOpen}
                setNewContactDialogOpen={closeNewContactDialog}
                newContactPayload={newContactPayload}
                setNewContactPayload={setNewContactPayload}
                imageFile={imageFile}
                setImageFile={setImageFile}
                selectedCompany={company}
                isCreating={isCreating}
                isSuccess={creationSucceeded}
                createNewContact={async (
                    businessCard?: BusinessCardImportDraft,
                    duplicateReviewToken: string | null = null,
                ) => {
                    invalidatePendingClose();
                    const operationGeneration = closeGenerationRef.current;
                    const isCurrent = () => closeGenerationRef.current === operationGeneration;
                    setCreationSucceeded(false);
                    setIsCreating(true);
                    try {
                        const imported = businessCard
                            ? await importBusinessCard(businessCard)
                            : null;
                        const newContact = imported?.contact
                            ?? await createContact({
                                ...newContactPayload,
                                duplicateReviewToken: duplicateReviewToken ?? undefined,
                            });
                        if (!isCurrent()) return;
                        const reusedContact = imported?.disposition === 'reused';
                        let avatarUploadFailed = false;
                        if (imageFile && !reusedContact) {
                            try {
                                await uploadContactPicture(newContact.id, imageFile);
                            } catch {
                                avatarUploadFailed = true;
                            }
                            if (!isCurrent()) return;
                        }
                        if (isCurrent()) setIsCreating(false);
                        let finalized = false;
                        return {
                            avatarUploadFailed,
                            avatarUploaded: imageFile != null && !reusedContact && !avatarUploadFailed,
                            finalize: () => {
                                if (finalized || !isCurrent()) return;
                                finalized = true;
                                setNewContactPayload(emptyContactPayload(company.id));
                                setImageFile(null);
                                toast.success(t(reusedContact ? 'toastCardAttached' : 'toastContactCreated'));
                                setCreationSucceeded(true);
                                invalidatePendingClose();
                                const closeGeneration = closeGenerationRef.current;
                                closeTimerRef.current = window.setTimeout(() => {
                                    if (closeGenerationRef.current !== closeGeneration) return;
                                    closeTimerRef.current = null;
                                    closeNewContactDialog(false);
                                    router.refresh();
                                }, 900);
                            },
                        };
                    } catch (error) {
                        if (!isCurrent()) return;
                        if (isFieldError(error) || businessCard) {
                            throw error;
                        }
                        toast.error(t('toastCreateContactFailed'));
                    } finally {
                        if (isCurrent()) setIsCreating(false);
                    }
                }}
            />
        </>
    );
}
