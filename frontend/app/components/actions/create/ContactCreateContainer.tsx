'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

import NewContactDialog, { NewContactForm } from '@/app/components/records/contacts/NewContactDialog';
import { createContact, importBusinessCard, isFieldError, uploadContactPicture } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { BusinessCardImportDraft, CreateContactPayload } from '@/app/lib/types';
import type { CreateDefaults } from '@/app/lib/actions/types';
import { publishRecordMutation } from '@/app/lib/record-mutation-events';

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
    const closeTimerRef = useRef<number | null>(null);
    const closeGenerationRef = useRef(0);

    const invalidatePendingClose = useCallback(() => {
        closeGenerationRef.current += 1;
        if (closeTimerRef.current == null) return;
        window.clearTimeout(closeTimerRef.current);
        closeTimerRef.current = null;
    }, []);

    const emitDismissLock = useCallback(() => {
        onDismissLockChange?.(
            creatingRef.current
            || importRetryRequiredRef.current
            || submissionPendingRef.current,
        );
    }, [onDismissLockChange]);

    useEffect(() => {
        invalidatePendingClose();
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
    }, [open, defaults?.companyId, invalidatePendingClose, onDismissLockChange]);

    useEffect(() => () => invalidatePendingClose(), [invalidatePendingClose]);

    const handleOpenChange = (next: boolean) => {
        if (!next && (
            creatingRef.current
            || importRetryRequiredRef.current
            || submissionPendingRef.current
        )) return;
        invalidatePendingClose();
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
            const imported = businessCard ? await importBusinessCard(businessCard) : null;
            const newContact = imported?.contact ?? await createContact(payload);
            let avatarUploadFailed = false;
            if (imageFile) {
                try {
                    await uploadContactPicture(newContact.id, imageFile);
                } catch {
                    avatarUploadFailed = true;
                }
            }
            creatingRef.current = false;
            setCreating(false);
            emitDismissLock();
            let finalized = false;
            return {
                avatarUploadFailed,
                avatarUploaded: imageFile != null && !avatarUploadFailed,
                finalize: () => {
                    if (finalized) return;
                    finalized = true;
                    toastSuccess(t('feedback.personCreated'));
                    publishRecordMutation('contact');
                    if (imported?.company) publishRecordMutation('company');
                    setSucceeded(true);
                    invalidatePendingClose();
                    const closeGeneration = closeGenerationRef.current;
                    closeTimerRef.current = window.setTimeout(() => {
                        if (closeGenerationRef.current !== closeGeneration) return;
                        closeTimerRef.current = null;
                        onOpenChange(false);
                        router.refresh();
                    }, 900);
                },
            };
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
                onCancel={() => {
                    invalidatePendingClose();
                    if (onCancel) onCancel();
                    else onOpenChange(false);
                }}
                newContactPayload={payload}
                setNewContactPayload={setPayload}
                imageFile={imageFile}
                setImageFile={setImageFile}
                isCreating={creating}
                isSuccess={succeeded}
                createNewContact={createNewContact}
                onRecoveredImport={(result) => {
                    publishRecordMutation('contact');
                    if (result.company) publishRecordMutation('company');
                    router.refresh();
                }}
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
            onRecoveredImport={(result) => {
                publishRecordMutation('contact');
                if (result.company) publishRecordMutation('company');
                router.refresh();
            }}
            onImportRetryRequiredChange={handleImportRetryRequiredChange}
            onSubmissionPendingChange={handleSubmissionPendingChange}
        />
    );
}
