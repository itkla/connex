'use client';

import { useState, type Ref } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { FaceSmileIcon, TrashIcon } from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';
import type { RecordComment, RecordCommentReactionKey } from '@/app/lib/types';
import { formatDateTime } from '@/app/lib/utils';
import NoteContent from '@/app/components/activity/notes/NoteContent';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';

const REACTION_KEYS = [
    'thumbs_up',
    'thumbs_down',
    'heart',
    'celebrate',
    'eyes',
    'laugh',
] as const satisfies readonly RecordCommentReactionKey[];

const REACTION_EMOJI: Record<RecordCommentReactionKey, string> = {
    thumbs_up: '👍',
    thumbs_down: '👎',
    heart: '❤️',
    celebrate: '🎉',
    eyes: '👀',
    laugh: '😄',
};

type Props = {
    comment: RecordComment;
    indented: boolean;
    highlighted: boolean;
    highlightRef?: Ref<HTMLLIElement>;
    canDelete: boolean;
    canReact: boolean;
    onDelete: (comment: RecordComment) => void;
    onToggleReaction: (comment: RecordComment, reaction: RecordCommentReactionKey) => void;
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
    canReact,
    onDelete,
    onToggleReaction,
}: Props) {
    const t = useTranslations('Comments');
    const locale = useLocale();
    const [pickerOpen, setPickerOpen] = useState(false);
    const deleted = comment.deletedAt != null;
    const authorName = comment.author?.displayName ?? t('formerMember');
    const reactions = comment.reactions ?? [];

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
                {(reactions.length > 0 || (canReact && !deleted)) && (
                    <div className="mt-1.5 flex flex-wrap items-center gap-1">
                        {reactions.map((summary) => (
                            <button
                                key={summary.reaction}
                                type="button"
                                disabled={!canReact || (deleted && !summary.reactedByMe)}
                                onClick={() => onToggleReaction(comment, summary.reaction)}
                                aria-label={t('reactionLabel', {
                                    reaction: t(`reaction_${summary.reaction}`),
                                    count: summary.count,
                                })}
                                aria-pressed={summary.reactedByMe}
                                className={cn(
                                    'inline-flex cursor-pointer items-center gap-1 rounded-full px-1.5 py-0.5 text-xs ring-1 ring-inset transition-colors',
                                    summary.reactedByMe
                                        ? 'bg-brand/10 ring-brand/40 text-foreground'
                                        : 'bg-muted ring-border text-muted-foreground hover:bg-muted/70',
                                    !canReact && 'pointer-events-none',
                                )}
                            >
                                <span aria-hidden>{REACTION_EMOJI[summary.reaction]}</span>
                                {summary.count}
                            </button>
                        ))}
                        {canReact && !deleted && (
                            <Popover open={pickerOpen} onOpenChange={setPickerOpen}>
                                <PopoverTrigger
                                    render={
                                        <button
                                            type="button"
                                            aria-label={t('addReaction')}
                                            title={t('addReaction')}
                                            className="inline-flex cursor-pointer items-center rounded-full p-2 text-muted-foreground transition-[color,background-color,opacity] hover:bg-muted hover:text-foreground focus-visible:opacity-100 pointer-fine:opacity-0 pointer-fine:group-hover:opacity-100"
                                        >
                                            <FaceSmileIcon className="size-4" />
                                        </button>
                                    }
                                />
                                <PopoverContent className="flex w-auto gap-1 p-1.5">
                                    {REACTION_KEYS.map((reaction) => (
                                        <button
                                            key={reaction}
                                            type="button"
                                            aria-label={t(`reaction_${reaction}`)}
                                            title={t(`reaction_${reaction}`)}
                                            onClick={() => {
                                                setPickerOpen(false);
                                                onToggleReaction(comment, reaction);
                                            }}
                                            className="grid size-11 cursor-pointer place-items-center rounded-md text-lg transition-colors hover:bg-muted"
                                        >
                                            {REACTION_EMOJI[reaction]}
                                        </button>
                                    ))}
                                </PopoverContent>
                            </Popover>
                        )}
                    </div>
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
