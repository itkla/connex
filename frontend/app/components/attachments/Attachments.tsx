'use client';

import { useEffect, useRef, useState, type DragEvent } from 'react';
import { useTranslations, useLocale } from 'next-intl';
import { LoaderCircle } from 'lucide-react';
import {
    ArrowDownTrayIcon,
    DocumentIcon,
    PaperClipIcon,
    PhotoIcon,
    TrashIcon,
} from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';
import { deleteAttachment, getAttachments, uploadAttachment } from '@/app/lib/api';
import { type Attachment } from '@/app/lib/types';
import { formatDate, formatFileSize, safeHref } from '@/app/lib/utils';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { onAttachmentsAdded } from '@/app/components/attachments/attachmentEvents';

type Props = {
    entityType: string;
    entityId: number;
    initialAttachments?: Attachment[];
    className?: string;
};

type Pending = { id: string; name: string };

function iconFor(contentType?: string) {
    if (contentType?.startsWith('image/')) return PhotoIcon;
    return DocumentIcon;
}

export default function Attachments({ entityType, entityId, initialAttachments, className }: Props) {
    const t = useTranslations('Attachments');
    const locale = useLocale();
    const inputRef = useRef<HTMLInputElement>(null);

    const [items, setItems] = useState<Attachment[]>(initialAttachments ?? []);
    const [pending, setPending] = useState<Pending[]>([]);
    const [busyId, setBusyId] = useState<number | null>(null);

    useEffect(() => {
        if (initialAttachments !== undefined) return;
        let active = true;
        getAttachments(entityType, entityId)
            .then((data) => {
                if (active) setItems(data);
            })
            .catch(() => {
                if (active) toastError(t('loadFailed'));
            });
        return () => {
            active = false;
        };
    }, [entityType, entityId, initialAttachments, t]);

    // pick up files attached elsewhere on the page (e.g. the entity action menu)
    useEffect(() => {
        return onAttachmentsAdded((detail) => {
            if (detail.entityType !== entityType || detail.entityId !== entityId) return;
            setItems((prev) => {
                const seen = new Set(prev.map((a) => a.id));
                const fresh = detail.attachments.filter((a) => !seen.has(a.id));
                return fresh.length > 0 ? [...fresh, ...prev] : prev;
            });
        });
    }, [entityType, entityId]);

    async function handleFiles(fileList: FileList | null) {
        if (!fileList || fileList.length === 0) return;
        const files = Array.from(fileList);
        const queued: Pending[] = files.map((file) => ({
            id: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
            name: file.name,
        }));
        setPending((prev) => [...prev, ...queued]);

        let successes = 0;
        await Promise.all(
            files.map(async (file, index) => {
                try {
                    const created = await uploadAttachment(entityType, entityId, file);
                    setItems((prev) => [created, ...prev]);
                    successes++;
                } catch {
                    toastError(t('uploadFailed', { name: file.name }));
                } finally {
                    const queuedId = queued[index].id;
                    setPending((prev) => prev.filter((p) => p.id !== queuedId));
                }
            }),
        );

        if (successes > 0) {
            toastSuccess(t('uploadedCount', { count: successes }));
        }
    }

    async function handleDelete(attachment: Attachment) {
        setBusyId(attachment.id);
        try {
            await deleteAttachment(attachment.id);
            setItems((prev) => prev.filter((a) => a.id !== attachment.id));
            toastSuccess(t('deleted'));
        } catch {
            toastError(t('deleteFailed'));
        } finally {
            setBusyId(null);
        }
    }

    const [dragActive, setDragActive] = useState(false);

    const onDropZoneDragOver = (e: DragEvent<HTMLButtonElement>) => {
        e.preventDefault();
        if (!dragActive) setDragActive(true);
    };
    const onDropZoneDragLeave = (e: DragEvent<HTMLButtonElement>) => {
        e.preventDefault();
        setDragActive(false);
    };
    const onDropZoneDrop = (e: DragEvent<HTMLButtonElement>) => {
        e.preventDefault();
        setDragActive(false);
        void handleFiles(e.dataTransfer.files);
    };

    const uploading = pending.length > 0;
    const isEmpty = items.length === 0 && pending.length === 0;

    return (
        <div className={cn(className)}>
            <div className="mb-3 flex h-8 items-center">
                <h2 className="px-6 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                    {t('title')} · {items.length}
                </h2>
            </div>

            <input
                ref={inputRef}
                type="file"
                multiple
                className="hidden"
                onChange={(e) => {
                    void handleFiles(e.target.files);
                    e.target.value = '';
                }}
            />

            <div className="overflow-hidden rounded-2xl bg-card ring-1 ring-border">
                {!isEmpty && (
                    <ul className="max-h-80 divide-y divide-border overflow-y-auto">
                        {pending.map((p) => (
                            <li key={p.id} className="flex items-center gap-3 px-4 py-3">
                                <LoaderCircle className="size-5 shrink-0 animate-spin text-muted-foreground" />
                                <span className="min-w-0 flex-1 truncate text-sm text-muted-foreground">
                                    {p.name}
                                </span>
                            </li>
                        ))}
                        {items.map((attachment) => {
                            const Icon = iconFor(attachment.contentType);
                            const deleting = busyId === attachment.id;
                            return (
                                <li
                                    key={attachment.id}
                                    className="flex items-center gap-3 px-4 py-3 transition-colors hover:bg-muted/40"
                                >
                                    <Icon className="size-5 shrink-0 text-muted-foreground" />
                                    <div className="min-w-0 flex-1">
                                        <a
                                            href={safeHref(attachment.url)}
                                            target="_blank"
                                            rel="noopener noreferrer"
                                            className="block truncate text-sm font-medium text-foreground hover:text-brand"
                                            title={attachment.fileName}
                                        >
                                            {attachment.fileName}
                                        </a>
                                        <p className="truncate text-xs text-muted-foreground">
                                            {formatFileSize(attachment.size)}
                                            {' · '}
                                            {t('by', { name: attachment.uploadedByName || t('unknownUser') })}
                                            {' · '}
                                            {formatDate(attachment.createdAt, locale)}
                                        </p>
                                    </div>
                                    <a
                                        href={safeHref(attachment.url)}
                                        download={attachment.fileName}
                                        className="shrink-0 rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                                        title={t('download')}
                                        aria-label={t('download')}
                                    >
                                        <ArrowDownTrayIcon className="size-4" />
                                    </a>
                                    <button
                                        type="button"
                                        onClick={() => handleDelete(attachment)}
                                        disabled={deleting}
                                        className="shrink-0 cursor-pointer rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive disabled:pointer-events-none disabled:opacity-50"
                                        title={t('delete')}
                                        aria-label={t('delete')}
                                    >
                                        {deleting ? (
                                            <LoaderCircle className="size-4 animate-spin" />
                                        ) : (
                                            <TrashIcon className="size-4" />
                                        )}
                                    </button>
                                </li>
                            );
                        })}
                    </ul>
                )}

                <div className={cn('p-2', !isEmpty && 'border-t border-border')}>
                    <button
                        type="button"
                        onClick={() => inputRef.current?.click()}
                        onDragOver={onDropZoneDragOver}
                        onDragLeave={onDropZoneDragLeave}
                        onDrop={onDropZoneDrop}
                        disabled={uploading}
                        className={cn(
                            'flex w-full cursor-pointer items-center justify-center gap-2 rounded-xl border-2 border-dashed text-sm transition-colors',
                            isEmpty ? 'flex-col px-4 py-10' : 'px-4 py-3',
                            dragActive
                                ? 'border-brand bg-brand/5 text-brand'
                                : 'border-border text-muted-foreground hover:border-muted-foreground/40 hover:bg-muted/60',
                            'disabled:pointer-events-none disabled:opacity-60',
                        )}
                    >
                        {uploading ? (
                            <LoaderCircle
                                className={cn('pointer-events-none animate-spin', isEmpty ? 'size-6' : 'size-4')}
                            />
                        ) : (
                            <PaperClipIcon
                                className={cn('pointer-events-none', isEmpty ? 'size-6' : 'size-4')}
                            />
                        )}
                        <span className="pointer-events-none">
                            {uploading ? t('uploading') : isEmpty ? t('empty') : t('addOrDrop')}
                        </span>
                    </button>
                </div>
            </div>
        </div>
    );
}
