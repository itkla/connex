'use client';

/* eslint-disable @next/next/no-img-element */

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import {
    ArrowDownTrayIcon,
    ArrowTopRightOnSquareIcon,
    TrashIcon,
} from '@heroicons/react/24/outline';
import { LoaderCircle } from 'lucide-react';

import {
    Sheet,
    SheetContent,
    SheetDescription,
    SheetFooter,
    SheetHeader,
    SheetTitle,
} from '@/components/ui/sheet';
import { Button } from '@/components/ui/button';

import { getAttachments } from '@/app/lib/api';
import { formatDateTime, formatFileSize } from '@/app/lib/utils';
import type { Attachment, Tag } from '@/app/lib/types';
import { classifyKind, KIND_ICON, KIND_LABEL_KEY, sourceMetaFor } from '@/app/components/library/files/fileMeta';
import FileGlyph from '@/app/components/library/files/FileGlyph';
import TagEditor from '@/app/components/records/contacts/TagEditor';

type Props = {
    attachment: Attachment | null;
    allTags: Tag[];
    onOpenChange: (open: boolean) => void;
    onSelect: (attachment: Attachment) => void;
    onDelete: (attachment: Attachment) => void;
    onTagsChanged: () => void;
};

export default function FileDetailSheet({
    attachment,
    allTags,
    onOpenChange,
    onSelect,
    onDelete,
    onTagsChanged,
}: Props) {
    const t = useTranslations('LibraryFiles');
    const locale = useLocale();

    const [shown, setShown] = useState<Attachment | null>(attachment);
    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect
        if (attachment) setShown(attachment);
    }, [attachment]);

    const [related, setRelated] = useState<Attachment[]>([]);
    const [relatedLoading, setRelatedLoading] = useState(false);

    useEffect(() => {
        if (!attachment) return;
        let active = true;
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setRelatedLoading(true);
        getAttachments(attachment.entityType, attachment.entityId)
            .then((list) => {
                if (active) setRelated(list.filter((x) => x.id !== attachment.id));
            })
            .catch(() => {
                if (active) setRelated([]);
            })
            .finally(() => {
                if (active) setRelatedLoading(false);
            });
        return () => {
            active = false;
        };
    }, [attachment]);

    const a = attachment ?? shown;
    const kind = a ? classifyKind(a.contentType, a.fileName) : 'other';
    const Icon = KIND_ICON[kind];
    const meta = a ? sourceMetaFor(a.entityType) : null;
    const recordLabel = a?.entityLabel || t('unknownRecord');

    return (
        <Sheet open={attachment !== null} onOpenChange={onOpenChange}>
            <SheetContent side="right" className="w-full gap-0 p-0 sm:max-w-md">
                {a && (
                    <>
                        <SheetHeader className="flex-row items-center gap-3 border-b border-border pr-12">
                            <FileGlyph attachment={a} kind={kind} />
                            <div className="min-w-0">
                                <SheetTitle className="truncate" title={a.fileName}>
                                    {a.fileName}
                                </SheetTitle>
                                <SheetDescription className="truncate">
                                    {t(KIND_LABEL_KEY[kind])} · {formatFileSize(a.size)}
                                </SheetDescription>
                            </div>
                        </SheetHeader>

                        <div className="flex-1 space-y-6 overflow-y-auto p-4">
                            <Preview attachment={a} kind={kind} noPreview={t('noPreview')} Icon={Icon} />

                            <dl className="space-y-3 text-sm">
                                <MetaRow label={t('detailType')}>
                                    <span className="inline-flex items-center gap-1.5">
                                        <Icon className="size-4 text-muted-foreground" />
                                        {t(KIND_LABEL_KEY[kind])}
                                    </span>
                                </MetaRow>
                                <MetaRow label={t('detailSize')}>
                                    <span className="tabular-nums">{formatFileSize(a.size)}</span>
                                </MetaRow>
                                <MetaRow label={t('detailRecord')}>
                                    {meta && a.entityId ? (
                                        <Link
                                            href={meta.href(a.entityId)}
                                            className="inline-flex items-center gap-1.5 text-foreground transition-colors hover:text-brand"
                                        >
                                            <meta.Icon className="size-4" />
                                            <span className="truncate">{recordLabel}</span>
                                        </Link>
                                    ) : (
                                        <span className="text-muted-foreground">{recordLabel}</span>
                                    )}
                                </MetaRow>
                                <MetaRow label={t('detailUploadedBy')}>{a.uploadedByName || t('unknownUser')}</MetaRow>
                                <MetaRow label={t('detailAdded')}>{formatDateTime(a.createdAt, locale)}</MetaRow>
                            </dl>

                            <div className="space-y-2">
                                <h3 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                                    {t('tagsTitle')}
                                </h3>
                                <div className="flex flex-wrap items-center gap-1.5">
                                    <TagEditor
                                        attachmentId={a.id}
                                        currentTags={a.tags ?? []}
                                        allTags={allTags}
                                        onChange={onTagsChanged}
                                    />
                                </div>
                            </div>

                            <div className="space-y-2">
                                <h3 className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                                    {t('relatedTitle')}
                                </h3>
                                {relatedLoading ? (
                                    <div className="flex items-center gap-2 px-1 py-3 text-sm text-muted-foreground">
                                        <LoaderCircle className="size-4 animate-spin" />
                                    </div>
                                ) : related.length === 0 ? (
                                    <p className="px-1 py-2 text-sm text-muted-foreground">{t('relatedEmpty')}</p>
                                ) : (
                                    <ul className="overflow-hidden rounded-xl ring-1 ring-border">
                                        {related.map((r) => {
                                            const rKind = classifyKind(r.contentType, r.fileName);
                                            return (
                                                <li key={r.id} className="border-b border-border last:border-b-0">
                                                    <button
                                                        type="button"
                                                        onClick={() => onSelect(r)}
                                                        className="flex w-full items-center gap-3 px-3 py-2.5 text-left transition-colors hover:bg-muted/50"
                                                    >
                                                        <FileGlyph attachment={r} kind={rKind} />
                                                        <span className="min-w-0 flex-1 truncate text-sm font-medium">
                                                            {r.fileName}
                                                        </span>
                                                        <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
                                                            {formatFileSize(r.size)}
                                                        </span>
                                                    </button>
                                                </li>
                                            );
                                        })}
                                    </ul>
                                )}
                            </div>
                        </div>

                        <SheetFooter className="flex-row gap-2 border-t border-border">
                            <Button asChild variant="outline" className="flex-1">
                                <a href={a.url} target="_blank" rel="noopener noreferrer">
                                    <ArrowTopRightOnSquareIcon className="size-4" />
                                    {t('open')}
                                </a>
                            </Button>
                            <Button asChild variant="outline" className="flex-1">
                                <a href={a.url} download={a.fileName}>
                                    <ArrowDownTrayIcon className="size-4" />
                                    {t('download')}
                                </a>
                            </Button>
                            <Button
                                type="button"
                                variant="ghost"
                                size="icon"
                                aria-label={t('delete')}
                                title={t('delete')}
                                onClick={() => onDelete(a)}
                                className="text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
                            >
                                <TrashIcon className="size-4" />
                            </Button>
                        </SheetFooter>
                    </>
                )}
            </SheetContent>
        </Sheet>
    );
}

/**
 * Meta row component for the file detail sheet.
 * @param label - The label to display.
 * @param children - The children to display.
 * @returns 
 */
function MetaRow({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <div className="flex items-baseline justify-between gap-4">
            <dt className="shrink-0 text-muted-foreground">{label}</dt>
            <dd className="min-w-0 truncate text-right font-medium text-foreground">{children}</dd>
        </div>
    );
}

/**
 * Preview component for the file detail sheet. Basically a wrapped iframe
 * @param attachment - The attachment to display.
 * @param kind - The kind of the attachment.
 * @param noPreview - The text to display if there is no preview.
 * @param Icon - The icon to display if there is no preview.
 * @returns 
 */
function Preview({
    attachment,
    kind,
    noPreview,
    Icon,
}: {
    attachment: Attachment;
    kind: ReturnType<typeof classifyKind>;
    noPreview: string;
    Icon: React.ComponentType<{ className?: string }>;
}) {
    if (kind === 'image') {
        return (
            <img
                src={attachment.url}
                alt={attachment.fileName}
                className="max-h-72 w-full rounded-xl bg-muted object-contain ring-1 ring-border"
            />
        );
    }
    if (kind === 'pdf') {
        return (
            <iframe
                src={attachment.url}
                title={attachment.fileName}
                className="h-80 w-full rounded-xl bg-muted ring-1 ring-border"
            />
        );
    }
    return (
        <div className="flex h-40 flex-col items-center justify-center gap-2 rounded-xl bg-muted/60 text-muted-foreground ring-1 ring-border">
            <Icon className="size-12" />
            <span className="text-xs">{noPreview}</span>
        </div>
    );
}