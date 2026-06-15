'use client';

import { type Contact, type Company, type Tag } from "@/app/lib/types";
import ContactCard from "@/app/components/records/contacts/ContactCard";
import { PlusIcon } from "@heroicons/react/24/outline";

import NewContactDialog from "@/app/components/records/contacts/NewContactDialog";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { CreateContactPayload } from "@/app/lib/types";
import { createContact, updateContact, isFieldError } from "@/app/lib/api";
import { uploadContactPicture } from "@/app/lib/utils";
import { toast } from "sonner";
import { useTranslations } from "next-intl";

export default function ContactsGrid({ contacts, company, allTags }: { contacts: Contact[], company: Company, allTags: Tag[] }) {
    const router = useRouter();
    const t = useTranslations('CompaniesContactsGrid');
    const [newContactDialogOpen, setNewContactDialogOpen] = useState(false);
    const [newContactPayload, setNewContactPayload] = useState<CreateContactPayload>({
        name: '',
        email: '',
        phone: '',
        title: '',
        companyId: company?.id,
    });
    const [imageFile, setImageFile] = useState<File | null>(null);
    const [isCreating, setIsCreating] = useState(false);
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
                        // onQuickEdit={onQuickEdit ? () => onQuickEdit(person) : undefined}
                        // onDelete={onDelete ? () => onDelete(person) : undefined}
                        />
                    ))}
                </ul>
            )}

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
                createNewContact={async () => {
                    setIsCreating(true);
                    try {
                        const newContact = await createContact(newContactPayload);
                        if (imageFile) {
                            const imageUrl = await uploadContactPicture(newContact.id, imageFile);
                            await updateContact(newContact.id, { ...newContactPayload, imageUrl });
                        }
                        setNewContactPayload({
                            name: '',
                            email: '',
                            phone: '',
                            title: '',
                            companyId: company?.id,
                        });
                        setImageFile(null);
                        setNewContactDialogOpen(false);
                        toast.success(t('toastContactCreated'));
                        router.refresh();
                    } catch (error) {
                        if (isFieldError(error)) {
                            throw error;
                        }
                        console.error(error);
                        toast.error(t('toastCreateContactFailed'));
                    } finally {
                        setIsCreating(false);
                    }
                }}
            />
        </>
    );
}