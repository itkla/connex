'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

import NewContactDialog, { NewContactForm } from '@/app/components/records/contacts/NewContactDialog';
import { createContact, isFieldError, updateContact } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { uploadContactPicture } from '@/app/lib/utils';
import type { CreateContactPayload } from '@/app/lib/types';
import type { CreateDefaults } from '@/app/lib/actions/types';

const EMPTY_DRAFT: CreateContactPayload = { name: '', email: '', phone: '', title: '' };

/**
 * Shell-owned contact quick-create. Reuses {@link NewContactDialog} and mirrors the ContactsBrowser
 * create flow. A company prefill from the current record is seeded on open and remains editable.
 * Field errors are surfaced by the dialog, which the create handler rethrows for.
 */
export default function ContactCreateContainer({
    open,
    onOpenChange,
    defaults,
    embedded = false,
    onCancel,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    defaults?: CreateDefaults;
    /** Renders the shell-less {@link NewContactForm} directly, for embedding in the morphing launcher. */
    embedded?: boolean;
    /** Cancel handler for embedded mode — steps back to the launcher selector. */
    onCancel?: () => void;
}) {
    const router = useRouter();
    const t = useTranslations('Actions');

    const [payload, setPayload] = useState<CreateContactPayload>(EMPTY_DRAFT);
    const [imageFile, setImageFile] = useState<File | null>(null);
    const [creating, setCreating] = useState(false);
    const [succeeded, setSucceeded] = useState(false);

    useEffect(() => {
        if (!open) return;
        const raf = window.requestAnimationFrame(() => {
            setPayload({ ...EMPTY_DRAFT, companyId: defaults?.companyId });
            setImageFile(null);
            setSucceeded(false);
        });
        return () => window.cancelAnimationFrame(raf);
    }, [open, defaults?.companyId]);

    const handleOpenChange = (next: boolean) => {
        if (!next && creating) return;
        onOpenChange(next);
    };

    const createNewContact = async () => {
        setSucceeded(false);
        setCreating(true);
        try {
            const newContact = await createContact(payload);
            if (imageFile) {
                const imageUrl = await uploadContactPicture(newContact.id, imageFile);
                await updateContact(newContact.id, { ...payload, imageUrl });
            }
            toastSuccess(t('feedback.personCreated'));
            setCreating(false);
            setSucceeded(true);
            setTimeout(() => {
                onOpenChange(false);
                router.refresh();
            }, 900);
        } catch (err) {
            setCreating(false);
            if (isFieldError(err)) throw err;
            toastError(err instanceof Error ? err.message : t('feedback.createFailed'));
        }
    };

    if (embedded) {
        return (
            <NewContactForm
                active
                onCancel={onCancel ?? (() => onOpenChange(false))}
                newContactPayload={payload}
                setNewContactPayload={setPayload}
                imageFile={imageFile}
                setImageFile={setImageFile}
                isCreating={creating}
                isSuccess={succeeded}
                createNewContact={createNewContact}
            />
        );
    }

    return (
        <NewContactDialog
            newContactDialogOpen={open}
            setNewContactDialogOpen={handleOpenChange}
            newContactPayload={payload}
            setNewContactPayload={setPayload}
            imageFile={imageFile}
            setImageFile={setImageFile}
            isCreating={creating}
            isSuccess={succeeded}
            createNewContact={createNewContact}
        />
    );
}
