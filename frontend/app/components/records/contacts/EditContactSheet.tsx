'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';

import QuickEditSheet, { type ContactDraft } from '@/app/components/records/contacts/QuickEditSheet';
import { getContactById, updateContact } from '@/app/lib/api';
import { type Contact, type UpdateContactPayload } from '@/app/lib/types';
import { uploadContactPicture } from '@/app/lib/utils';

function toDraft(c: Contact): ContactDraft {
    return {
        name: c.name ?? '',
        email: c.email ?? '',
        phone: c.phone ?? '',
        title: c.title ?? '',
    };
}

function diffDraft(a: ContactDraft, b: ContactDraft): boolean {
    return a.name !== b.name || a.email !== b.email || a.phone !== b.phone || a.title !== b.title;
}

export default function EditContactSheet({
    contact,
    open,
    onOpenChange,
}: {
    contact: Contact;
    open: boolean;
    onOpenChange: (open: boolean) => void;
}) {
    const router = useRouter();
    const t = useTranslations('ContactsEditSheet');
    const [draft, setDraft] = useState<ContactDraft>(() => toDraft(contact));
    const [imageFile, setImageFile] = useState<File | null>(null);
    const [isSaving, setIsSaving] = useState(false);

    const handleOpenChange = (next: boolean) => {
        onOpenChange(next);
        if (!next) {
            setDraft(toDraft(contact));
            setImageFile(null);
        }
    };

    const saveEdits = async () => {
        const original = toDraft(contact);
        const textChanged = diffDraft(original, draft);
        const pictureChanged = imageFile !== null;

        if (!textChanged && !pictureChanged) {
            toast.info(t('toastNoChanges'));
            handleOpenChange(false);
            return;
        }

        if (!draft.name.trim()) {
            toast.error(t('toastNameRequired'));
            return;
        }

        setIsSaving(true);
        try {
            let imageUrl: string | undefined;
            if (pictureChanged && imageFile) {
                imageUrl = await uploadContactPicture(contact.id, imageFile);
            }

            const payload: UpdateContactPayload = {
                name: draft.name.trim(),
                email: draft.email.trim() || undefined,
                phone: draft.phone.trim() || undefined,
                title: draft.title.trim() || undefined,
                companyId: contact.companyId ?? contact.company?.id ?? null,
                imageUrl: imageUrl ?? contact.imageUrl ?? undefined,
            };
            await updateContact(contact.id, payload);

            toast.success(t('toastContactUpdated'), {
                style: { backgroundColor: 'var(--color-brand)', color: 'white' },
            });
            handleOpenChange(false);

            // on success, update the form fields to reflect the updated info so that stale info isn't accidentally sent again
            // setDraft(toDraft(contact));
            // setImageFile(null);

            // TODO: change this to optimistic ui update. we should rely on previous data to update from instead of querying the backend
            const updatedContact = await getContactById(contact.id);
            if (updatedContact) {
                setDraft(toDraft(updatedContact));
                setImageFile(null);
            }

            router.refresh();
        } catch (err) {
            toast.error(err instanceof Error ? err.message : t('toastFailedSave'), {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
        } finally {
            setIsSaving(false);
        }
    };

    return (
        <QuickEditSheet
            editSheetOpen={open}
            setEditSheetOpen={handleOpenChange}
            selectedIds={new Set([contact.id])}
            selectedContacts={[contact]}
            drafts={{ [contact.id]: draft }}
            updateDraft={(_id, patch) => setDraft((prev) => ({ ...prev, ...patch }))}
            imageFiles={{ [contact.id]: imageFile }}
            updateImageFile={(_id, file) => setImageFile(file)}
            isSaving={isSaving}
            saveEdits={saveEdits}
        />
    );
}
