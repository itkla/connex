'use client';

import { useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';
import { toastError, toastSuccess } from '@/app/lib/toast';

import QuickEditSheet, { type ContactDraft } from '@/app/components/records/contacts/QuickEditSheet';
import { CustomFieldsEditSection, type CustomFieldsEditHandle } from '@/app/components/records/CustomFieldsEditSection';
import { getContactById, updateContact, uploadContactPicture } from '@/app/lib/api';
import { type Contact, type UpdateContactPayload } from '@/app/lib/types';

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
    const cfRef = useRef<CustomFieldsEditHandle>(null);

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
        const customChanged = cfRef.current?.hasChanges() ?? false;

        if (!textChanged && !pictureChanged && !customChanged) {
            toast.info(t('toastNoChanges'));
            handleOpenChange(false);
            return;
        }

        if (!draft.name.trim()) {
            toast.error(t('toastNameRequired'));
            return;
        }

        setIsSaving(true);
        let committedChanges = false;
        try {
            if (textChanged) {
                const payload: UpdateContactPayload = {
                    name: draft.name.trim(),
                    email: draft.email.trim() || undefined,
                    phone: draft.phone.trim() || undefined,
                    title: draft.title.trim() || undefined,
                    companyId: contact.companyId ?? contact.company?.id ?? null,
                };
                await updateContact(contact.id, payload);
                committedChanges = true;
            }

            if (customChanged) {
                await cfRef.current?.save();
                committedChanges = true;
            }

            if (pictureChanged && imageFile) {
                await uploadContactPicture(contact.id, imageFile);
            }

            toastSuccess(t('toastContactUpdated'));
            setImageFile(null);
            onOpenChange(false);
            router.refresh();
        } catch (err) {
            if (committedChanges) {
                const updatedContact = await getContactById(contact.id).catch(() => null);
                if (updatedContact) setDraft(toDraft(updatedContact));
                toastError(t('toastPartiallySaved'));
                router.refresh();
            } else {
                toastError(err instanceof Error ? err.message : t('toastFailedSave'));
            }
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
            customFieldsSlot={<CustomFieldsEditSection ref={cfRef} entityType="person" entityId={contact.id} />}
        />
    );
}
