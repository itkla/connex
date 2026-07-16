'use client';

import { type Contact, type Company, type Tag } from "@/app/lib/types";
import ContactCard from "@/app/components/records/contacts/ContactCard";
import { PlusIcon } from "@heroicons/react/24/outline";

import NewContactDialog from "@/app/components/records/contacts/NewContactDialog";
import { useState } from "react";
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

    const closeNewContactDialog = (open: boolean) => {
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
                <button onClick={() => {
                    setNewContactDialogOpen(true);
                }}>
                    <PlusIcon className="size-4 text-muted-foreground hover:text-foreground transition-colors duration-300 cursor-pointer" />
                </button>
            </div>
            {contacts.length === 0 ? (
                <div className="overflow-hidden rounded-2xl bg-muted ring-1 ring-border">
                    <p className="px-6 py-6 text-sm text-muted-foreground">{t('noContacts')}</p>
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
                createNewContact={async (businessCard?: BusinessCardImportDraft) => {
                    setCreationSucceeded(false);
                    setIsCreating(true);
                    try {
                        const newContact = businessCard
                            ? (await importBusinessCard(businessCard)).contact
                            : await createContact(newContactPayload);
                        let avatarUploadFailed = false;
                        if (imageFile) {
                            try {
                                await uploadContactPicture(newContact.id, imageFile);
                            } catch {
                                avatarUploadFailed = true;
                            }
                        }
                        setIsCreating(false);
                        let finalized = false;
                        return {
                            avatarUploadFailed,
                            avatarUploaded: imageFile != null && !avatarUploadFailed,
                            finalize: () => {
                                if (finalized) return;
                                finalized = true;
                                setNewContactPayload(emptyContactPayload(company.id));
                                setImageFile(null);
                                toast.success(t('toastContactCreated'));
                                setCreationSucceeded(true);
                                setTimeout(() => {
                                    closeNewContactDialog(false);
                                    router.refresh();
                                }, 900);
                            },
                        };
                    } catch (error) {
                        if (isFieldError(error) || businessCard) {
                            throw error;
                        }
                        toast.error(t('toastCreateContactFailed'));
                    } finally {
                        setIsCreating(false);
                    }
                }}
            />
        </>
    );
}
