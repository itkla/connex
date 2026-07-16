'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

import NewContactDialog, { NewContactForm } from '@/app/components/records/contacts/NewContactDialog';
import { createContact, importBusinessCard, isFieldError, uploadContactPicture } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { BusinessCardImportDraft, CreateContactPayload } from '@/app/lib/types';
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
    onDismissLockChange,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    defaults?: CreateDefaults;
    /** Renders the shell-less {@link NewContactForm} directly, for embedding in the morphing launcher. */
    embedded?: boolean;
    /** Cancel handler for embedded mode — steps back to the launcher selector. */
    onCancel?: () => void;
    onDismissLockChange?: (locked: boolean) => void;
}) {
    const router = useRouter();
    const t = useTranslations('Actions');

    const [payload, setPayload] = useState<CreateContactPayload>(EMPTY_DRAFT);
    const [imageFile, setImageFile] = useState<File | null>(null);
    const [creating, setCreating] = useState(false);
    const [succeeded, setSucceeded] = useState(false);
    const creatingRef = useRef(false);
    const importRetryRequiredRef = useRef(false);
    const submissionPendingRef = useRef(false);

    const emitDismissLock = useCallback(() => {
        onDismissLockChange?.(
            creatingRef.current
            || importRetryRequiredRef.current
            || submissionPendingRef.current,
        );
    }, [onDismissLockChange]);

    useEffect(() => {
        if (!open) return;
        const raf = window.requestAnimationFrame(() => {
            setPayload({ ...EMPTY_DRAFT, companyId: defaults?.companyId });
            setImageFile(null);
            setSucceeded(false);
            creatingRef.current = false;
            importRetryRequiredRef.current = false;
            submissionPendingRef.current = false;
            onDismissLockChange?.(false);
        });
        return () => window.cancelAnimationFrame(raf);
    }, [open, defaults?.companyId, onDismissLockChange]);

    const handleOpenChange = (next: boolean) => {
        if (!next && (
            creatingRef.current
            || importRetryRequiredRef.current
            || submissionPendingRef.current
        )) return;
        if (!next) onDismissLockChange?.(false);
        onOpenChange(next);
    };

    const handleImportRetryRequiredChange = (required: boolean) => {
        importRetryRequiredRef.current = required;
        emitDismissLock();
    };

    const handleSubmissionPendingChange = (pending: boolean) => {
        submissionPendingRef.current = pending;
        emitDismissLock();
    };

    const createNewContact = async (businessCard?: BusinessCardImportDraft) => {
        setSucceeded(false);
        creatingRef.current = true;
        setCreating(true);
        emitDismissLock();
        try {
            const newContact = businessCard
                ? (await importBusinessCard(businessCard)).contact
                : await createContact(payload);
            let avatarUploadFailed = false;
            if (imageFile) {
                try {
                    await uploadContactPicture(newContact.id, imageFile);
                } catch {
                    avatarUploadFailed = true;
                }
            }
            if (!avatarUploadFailed) toastSuccess(t('feedback.personCreated'));
            creatingRef.current = false;
            setCreating(false);
            setSucceeded(true);
            emitDismissLock();
            setTimeout(() => {
                onOpenChange(false);
                router.refresh();
            }, 900);
            return { avatarUploadFailed };
        } catch (err) {
            creatingRef.current = false;
            setCreating(false);
            emitDismissLock();
            if (isFieldError(err) || businessCard) throw err;
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
                onRecoveredImport={() => router.refresh()}
                onImportRetryRequiredChange={handleImportRetryRequiredChange}
                onSubmissionPendingChange={handleSubmissionPendingChange}
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
            onRecoveredImport={() => router.refresh()}
            onImportRetryRequiredChange={handleImportRetryRequiredChange}
            onSubmissionPendingChange={handleSubmissionPendingChange}
        />
    );
}
