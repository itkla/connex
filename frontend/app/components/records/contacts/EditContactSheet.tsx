'use client';

import { useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import { useRecordEditConflict } from '@/app/hooks/useRecordEditConflict';
import { toastError, toastInfo, toastSuccess } from '@/app/lib/toast';
import QuickEditSheet, { type ContactDraft } from './QuickEditSheet';
import ContactEditConflictDialog from './ContactEditConflictDialog';
import { contactDraftChanged, reconcileSavedContactDraft, submitContactDraftUpdate, toContactDraft } from './contactEditConflict';
import { CustomFieldsEditSection, type CustomFieldsEditHandle } from '@/app/components/records/CustomFieldsEditSection';
import { ApiError, getContactById, uploadContactPictureRecord } from '@/app/lib/api';
import { renderedWorkspaceId, workspacePinnedRequest } from '@/app/lib/renderedWorkspace';
import type { Contact } from '@/app/lib/types';

export default function EditContactSheet({ contact, open, onOpenChange, onContactUpdated }: {
    contact: Contact;
    open: boolean;
    onOpenChange: (open: boolean) => void;
    onContactUpdated?: (saved: Contact) => void;
}) {
    const router = useRouter();
    const t = useTranslations('ContactsEditSheet');
    const showApiError = useApiErrorToast('ContactsEditSheet');
    const [base, setBase] = useState(contact);
    const [originWorkspaceId, setOriginWorkspaceId] = useState(renderedWorkspaceId);
    const [draft, setDraft] = useState<ContactDraft>(() => toContactDraft(contact));
    const draftRevisionRef = useRef(0);
    const [imageFile, setImageFile] = useState<File | null>(null);
    const [isSaving, setIsSaving] = useState(false);
    const [reloadRevision, setReloadRevision] = useState(0);
    const { conflict, captureConflict, clearConflict } = useRecordEditConflict(getContactById);
    const cfRef = useRef<CustomFieldsEditHandle>(null);
    const [customFieldsDirty, setCustomFieldsDirty] = useState(false);
    const { fieldErrors, setFieldErrors, reset: resetFieldErrors, clearError } = useFieldErrors();
    const [wasOpen, setWasOpen] = useState(open);
    if (open !== wasOpen) {
        setWasOpen(open);
        clearConflict();
        resetFieldErrors();
        if (open) {
            setOriginWorkspaceId(renderedWorkspaceId());
            setBase(contact);
            setDraft(toContactDraft(contact));
            setImageFile(null);
        }
    }

    const handleOpenChange = (next: boolean) => {
        if (!next && isSaving) return;
        if (!next) clearConflict();
        onOpenChange(next);
    };

    const saveEdits = async () => {
        if (isSaving) return;
        const origin = workspacePinnedRequest(originWorkspaceId);
        resetFieldErrors();
        if (!contactDraftChanged(toContactDraft(base), draft) && !imageFile && !cfRef.current?.hasChanges()) {
            toastInfo(t('toastNoChanges'));
            handleOpenChange(false);
            return;
        }
        if (!draft.name.trim()) {
            setFieldErrors({ name: t('toastNameRequired') });
            requestAnimationFrame(() => document.getElementById(`name-${base.id}`)?.focus());
            return;
        }
        const submittedRevision = draftRevisionRef.current;
        setIsSaving(true);
        let committedChanges = false;
        try {
            const saved = await submitContactDraftUpdate(base, draft, origin);
            committedChanges = true;
            setBase(saved);
            onContactUpdated?.(saved);
            setDraft((current) => reconcileSavedContactDraft(current, draft, saved));
            await cfRef.current?.save(origin);
            if (imageFile) {
                const pictured = await uploadContactPictureRecord(base.id, imageFile, origin);
                setImageFile(null);
                onContactUpdated?.(pictured);
                if (saved.version !== undefined && pictured.version !== saved.version + 1) {
                    await captureConflict(new ApiError('The record changed since this edit was opened', 409, 'STALE_RECORD'), saved, origin);
                    return;
                }
                setBase(pictured);
                setDraft((current) => ({ ...current, expectedVersion: pictured.version }));
            }
            if (draftRevisionRef.current !== submittedRevision) return;
            toastSuccess(t('toastContactUpdated'));
            onOpenChange(false);
            router.refresh();
        } catch (err) {
            if (committedChanges) {
                toastError(t('toastPartiallySaved'));
                router.refresh();
            } else if (!await captureConflict(err, base, origin)) showApiError(err, 'toastFailedSave');
        } finally {
            setIsSaving(false);
        }
    };

    return (
        <>
            <QuickEditSheet
                key={reloadRevision}
                editSheetOpen={open}
                setEditSheetOpen={handleOpenChange}
                selectedIds={new Set([base.id])}
                selectedContacts={[base]}
                drafts={{ [base.id]: draft }}
                updateDraft={(_id, patch) => {
                    draftRevisionRef.current += 1;
                    for (const key of Object.keys(patch)) clearError(key);
                    setDraft((prev) => ({ ...prev, ...patch }));
                }}
                imageFiles={{ [base.id]: imageFile }}
                updateImageFile={(_id, file) => setImageFile(file)}
                isSaving={isSaving}
                saveEdits={saveEdits}
                fieldErrors={{ [base.id]: fieldErrors }}
                customFieldsDirty={customFieldsDirty}
                customFieldsSlot={(
                    <CustomFieldsEditSection ref={cfRef} entityType="person" entityId={base.id} onDirtyChange={setCustomFieldsDirty} />
                )}
            />
            {open && conflict && <ContactEditConflictDialog
                conflict={conflict}
                onKeepEditing={clearConflict}
                onReload={(current) => {
                    setBase(current);
                    setDraft(toContactDraft(current));
                    setImageFile(null);
                    resetFieldErrors();
                    setCustomFieldsDirty(false);
                    setReloadRevision((revision) => revision + 1);
                    clearConflict();
                }}
            />}
        </>
    );
}
