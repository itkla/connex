'use client';

import { useRef, useState, type ChangeEvent } from 'react';
import { useTranslations } from 'next-intl';

import { createAttachment } from '@/app/lib/api';
import { type Attachment } from '@/app/lib/types';
import { uploadFile } from '@/app/lib/utils';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { emitAttachmentsAdded } from '@/app/components/attachments/attachmentEvents';

/**
 * Shared attachment-upload behavior for the entity action menus. Wires a hidden
 * <input type="file"> to the existing upload pipeline (uploadFile + createAttachment),
 * then emits an event so the sibling <Attachments> panel picks up the new files
 * without re-running the whole page's server data fetch.
 * 
 * Very hacky way of doing things, but it works nonetheless.
 *
 * @param entityType - the owning entity type (e.g. "company", "person", "deal", "user")
 * @param entityId - the owning entity id
 */
export function useAttachmentUploader(entityType: string, entityId: number) {
    const t = useTranslations('Attachments');
    const inputRef = useRef<HTMLInputElement>(null);
    const [uploading, setUploading] = useState(false);

    const openPicker = () => inputRef.current?.click();

    const onFilesSelected = async (e: ChangeEvent<HTMLInputElement>) => {
        const files = Array.from(e.target.files ?? []);
        e.target.value = '';
        if (files.length === 0) return;

        setUploading(true);
        const created: Attachment[] = [];
        await Promise.all(
            files.map(async (file) => {
                try {
                    const uploaded = await uploadFile(entityType, entityId, file);
                    const attachment = await createAttachment({
                        entityType,
                        entityId,
                        fileName: uploaded.fileName,
                        url: uploaded.url,
                        contentType: uploaded.contentType,
                        size: uploaded.size,
                    });
                    created.push(attachment);
                } catch {
                    toastError(t('uploadFailed', { name: file.name }));
                }
            }),
        );
        setUploading(false);

        if (created.length > 0) {
            toastSuccess(t('uploadedCount', { count: created.length }));
            emitAttachmentsAdded({ entityType, entityId, attachments: created });
        }
    };

    return { inputRef, uploading, openPicker, onFilesSelected };
}