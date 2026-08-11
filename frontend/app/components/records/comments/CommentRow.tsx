'use client';

import type { Ref } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { TrashIcon } from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';
import type { RecordComment } from '@/app/lib/types';
import { formatDateTime } from '@/app/lib/utils';
import NoteContent from '@/app/components/activity/notes/NoteContent';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';

type Props = {
    comment: RecordComment;
    indented: boolean;
    highlighted: boolean;
    highlightRef?: Ref<HTMLLIElement>;
    canDelete: boolean;
    onDelete: (comment: RecordComment) => void;
};

/**
 * One comment row: author identity (with an erased-account fallback), timestamp,
 * content or redaction tombstone, and a delete affordance that stays visible on
 * coarse pointers and reveals on hover for fine ones.
 */
export default function CommentRow({
    comment,
    indented,
    highlighted,
    highlightRef,
    canDelete,
    onDelete,
}: Props) {
    const t = useTranslations('Comments');
    const locale = useLocale();
    const deleted = comment.deletedAt != null;
    const authorName = comment.author?.displayName ?? t('formerMember');

    return (
        <li
            ref={highlightRef}
            className={cn(
                'group flex scroll-mt-24 items-start gap-3 rounded-lg px-2 py-1.5 transition-colors duration-700',
                indented && 'ml-9',
                highlighted && 'bg-brand-light/40',
            )}
        >
            <Avatar className="mt-0.5 size-7 shrink-0">
                <AvatarImage src={comment.author?.profilePictureUrl ?? undefined} alt="" />
                <AvatarFallback className="text-[0.65rem]">
                    {authorName.slice(0, 1).toUpperCase()}
                </AvatarFallback>
            </Avatar>
            <div className="min-w-0 flex-1">
                <p className="flex items-baseline gap-2 text-sm">
                    <span className="font-medium text-foreground">{authorName}</span>
                    <span className="text-xs text-muted-foreground">
                        {formatDateTime(comment.createdAt, locale)}
                    </span>
                </p>
                {deleted ? (
                    <p className="text-sm italic text-muted-foreground">{t('deletedComment')}</p>
                ) : (
                    <NoteContent
                        content={comment.content ?? ''}
                        references={comment.references}
                        className="text-sm text-foreground"
                        block
                    />
                )}
            </div>
            {canDelete && (
                <button
                    type="button"
                    onClick={() => onDelete(comment)}
                    className="shrink-0 cursor-pointer rounded-md p-2 text-muted-foreground transition-[color,background-color,opacity] hover:bg-destructive/10 hover:text-destructive focus-visible:opacity-100 pointer-fine:opacity-0 pointer-fine:group-hover:opacity-100"
                    title={t('delete')}
                    aria-label={t('delete')}
                >
                    <TrashIcon className="size-4" />
                </button>
            )}
        </li>
    );
}
