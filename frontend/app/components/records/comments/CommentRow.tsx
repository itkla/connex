'use client';

import { useState, type Ref } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { CheckCircleIcon, FaceSmileIcon, TrashIcon } from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';
import type { RecordComment, RecordCommentReactionKey } from '@/app/lib/types';
import { formatDateTime, formatRelativeTime } from '@/app/lib/utils';
import { useLiveNow } from '@/app/hooks/useNow';
import NoteContent from '@/app/components/activity/notes/NoteContent';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

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
    isRoot: boolean;
    showRail: boolean;
    highlighted: boolean;
    highlightRef?: Ref<HTMLDivElement>;
    canReact: boolean;
    canDelete: boolean;
    canResolve?: boolean;
    onDelete: (comment: RecordComment) => void;
    onToggleReaction: (comment: RecordComment, reaction: RecordCommentReactionKey) => void;
    onResolve?: () => void;
};

/**
 * One comment on the thread rail: the avatar cell (with the connecting rail
 * segment) and the content cell with an anchored author line, relative
 * timestamp, body or redaction tombstone, reaction chips, and a single quiet
 * action cluster that reveals on hover and stays visible on coarse pointers.
 * Rendered as two cells of the parent thread grid.
 */
export default function CommentRow({
    comment,
    isRoot,
    showRail,
    highlighted,
    highlightRef,
    canReact,
    canDelete,
    canResolve = false,
    onDelete,
    onToggleReaction,
    onResolve,
}: Props) {
    const t = useTranslations('Comments');
    const locale = useLocale();
    const now = useLiveNow();
    const [pickerOpen, setPickerOpen] = useState(false);
    const deleted = comment.deletedAt != null;
    const authorName = comment.author?.displayName ?? t('formerMember');
    const reactions = comment.reactions ?? [];
    const showCluster = (!deleted && (canReact || canDelete)) || (canResolve && onResolve != null);

    return (
        <>
            <div className="flex flex-col items-center">
                <Avatar className={cn('shrink-0', isRoot ? 'size-8' : 'mt-0.5 size-7')}>
                    <AvatarImage src={comment.author?.profilePictureUrl ?? undefined} alt="" />
                    <AvatarFallback className={cn(isRoot ? 'text-xs' : 'text-[0.65rem]')}>
                        {authorName.slice(0, 1).toUpperCase()}
                    </AvatarFallback>
                </Avatar>
                {showRail && <div className="mt-1.5 w-px flex-1 rounded-full bg-border" aria-hidden />}
            </div>

            <div
                ref={highlightRef}
                className={cn(
                    'group relative -mx-2 mb-3 min-w-0 scroll-mt-24 rounded-lg px-2 pb-0.5 transition-colors duration-500 motion-reduce:transition-none',
                    highlighted && 'bg-brand-light/40',
                )}
            >
                <p className="flex min-w-0 items-baseline gap-2 pr-20 text-sm leading-7">
                    <span className="truncate font-medium text-foreground">{authorName}</span>
                    <time
                        dateTime={comment.createdAt}
                        title={formatDateTime(comment.createdAt, locale)}
                        className="shrink-0 text-xs text-muted-foreground"
                    >
                        {formatRelativeTime(comment.createdAt, locale, now)}
                    </time>
                </p>

                {deleted ? (
                    <p className="text-sm italic leading-relaxed text-muted-foreground">
                        {t('deletedComment')}
                    </p>
                ) : (
                    <NoteContent
                        content={comment.content ?? ''}
                        references={comment.references}
                        className="text-sm leading-relaxed text-foreground"
                        block
                    />
                )}

                {reactions.length > 0 && (
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
                                    'inline-flex cursor-pointer items-center gap-1 rounded-full px-2 py-0.5 text-xs ring-1 ring-inset transition-colors',
                                    summary.reactedByMe
                                        ? 'bg-brand/10 text-foreground ring-brand/40'
                                        : 'bg-muted text-muted-foreground ring-transparent hover:ring-border',
                                    'disabled:pointer-events-none disabled:opacity-60',
                                )}
                            >
                                <span aria-hidden>{REACTION_EMOJI[summary.reaction]}</span>
                                {summary.count}
                            </button>
                        ))}
                    </div>
                )}

                {showCluster && (
                    <div
                        className={cn(
                            'absolute -top-1 right-0 flex items-center gap-0.5 rounded-lg bg-card p-0.5',
                            'opacity-0 transition-opacity duration-150 focus-within:opacity-100 group-hover:opacity-100',
                            'pointer-coarse:opacity-100 motion-reduce:transition-none',
                            pickerOpen && 'opacity-100',
                        )}
                    >
                        {canReact && !deleted && (
                            <Popover open={pickerOpen} onOpenChange={setPickerOpen}>
                                <PopoverTrigger
                                    render={
                                        <button
                                            type="button"
                                            aria-label={t('addReaction')}
                                            title={t('addReaction')}
                                            className="cursor-pointer rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
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
                        {canResolve && onResolve && (
                            <Tooltip>
                                <TooltipTrigger asChild>
                                    <button
                                        type="button"
                                        aria-label={t('resolve')}
                                        onClick={onResolve}
                                        className="cursor-pointer rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                                    >
                                        <CheckCircleIcon className="size-4" />
                                    </button>
                                </TooltipTrigger>
                                <TooltipContent>{t('resolve')}</TooltipContent>
                            </Tooltip>
                        )}
                        {canDelete && !deleted && (
                            <Tooltip>
                                <TooltipTrigger asChild>
                                    <button
                                        type="button"
                                        aria-label={t('delete')}
                                        onClick={() => onDelete(comment)}
                                        className="cursor-pointer rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive"
                                    >
                                        <TrashIcon className="size-4" />
                                    </button>
                                </TooltipTrigger>
                                <TooltipContent>{t('delete')}</TooltipContent>
                            </Tooltip>
                        )}
                    </div>
                )}
            </div>
        </>
    );
}
