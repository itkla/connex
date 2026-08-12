'use client';

import { useEffect, useRef, useState, type Ref } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { CheckCircleIcon as CheckCircleSolidIcon } from '@heroicons/react/24/solid';
import { ChevronDownIcon } from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';
import type { RecordComment, RecordCommentReactionKey, RecordCommentThread } from '@/app/lib/types';
import { DRAFT_VERSIONS, readDraft, type DraftKeyParts } from '@/app/lib/formDrafts';
import { useFormDraft } from '@/app/hooks/useFormDraft';
import { formatRelativeTime, formatUtcDateTime } from '@/app/lib/utils';
import { useLiveNow } from '@/app/hooks/useNow';
import { commentPlainText, isCommentDraft } from '@/app/components/records/comments/commentText';
import CommentComposer from '@/app/components/records/comments/CommentComposer';
import CommentRow from '@/app/components/records/comments/CommentRow';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Button } from '@/components/ui/button';

export type CommentAuthorIdentity = {
    id: number;
    displayName: string;
    profilePictureUrl?: string | null;
};

type Props = {
    thread: RecordCommentThread;
    currentUser: CommentAuthorIdentity;
    activeWorkspaceId: number | null;
    canComment: boolean;
    canModerate: boolean;
    highlightedCommentId: string | null;
    highlightRef: Ref<HTMLDivElement>;
    forceExpanded: boolean;
    transitioning: boolean;
    onSubmitReply: (
        thread: RecordCommentThread,
        content: string,
        clientToken: string,
    ) => Promise<boolean>;
    onSubmitEdit: (comment: RecordComment, content: string) => Promise<boolean>;
    onCopyLink: (comment: RecordComment) => void;
    onDelete: (comment: RecordComment) => void;
    onToggleReaction: (comment: RecordComment, reaction: RecordCommentReactionKey) => void;
    onResolve: () => void;
    onReopen: () => void;
};

function resolverName(thread: RecordCommentThread): string | null {
    if (thread.resolvedByUserId == null) return null;
    const participant = thread.comments.find(
        (comment) => comment.author?.id === thread.resolvedByUserId,
    );
    return participant?.author?.displayName ?? null;
}

/**
 * One discussion thread: the root comment and its replies connected by a
 * single avatar rail, a faux-input reply stub that expands into a
 * draft-persisted composer, and thread-level resolution. The thread owns its
 * reply state and idempotency token; the parent owns every mutation. Resolved
 * threads recede to a one-line summary that expands inline.
 */
export default function CommentThread({
    thread,
    currentUser,
    activeWorkspaceId,
    canComment,
    canModerate,
    highlightedCommentId,
    highlightRef,
    forceExpanded,
    transitioning,
    onSubmitReply,
    onSubmitEdit,
    onCopyLink,
    onDelete,
    onToggleReaction,
    onResolve,
    onReopen,
}: Props) {
    const t = useTranslations('Comments');
    const locale = useLocale();
    const now = useLiveNow();
    const resolved = thread.state === 'resolved';
    const [expandedOverride, setExpandedOverride] = useState<boolean | null>(null);
    const [lastForceExpanded, setLastForceExpanded] = useState(forceExpanded);
    if (forceExpanded !== lastForceExpanded) {
        setLastForceExpanded(forceExpanded);
        if (forceExpanded) setExpandedOverride(null);
    }
    const expanded = expandedOverride ?? forceExpanded;

    const [replyOpen, setReplyOpen] = useState(false);
    const [replyValue, setReplyValue] = useState('');
    const [replySubmitting, setReplySubmitting] = useState(false);
    const replyToken = useRef<string | null>(null);

    const [replyDraftKeyParts] = useState<DraftKeyParts>(() => ({
        userId: currentUser.id,
        workspaceId: activeWorkspaceId,
        formType: 'comment',
        scope: `reply:${thread.id}`,
    }));
    const replyDraft = useFormDraft<{ content: string }>({
        keyParts: replyDraftKeyParts,
        version: DRAFT_VERSIONS.comment,
    });

    useEffect(() => {
        const timer = window.setTimeout(() => {
            const stored = readDraft(replyDraftKeyParts, { version: DRAFT_VERSIONS.comment });
            const content = stored && isCommentDraft(stored.data) ? stored.data.content : '';
            if (content.length > 0) {
                setReplyValue((current) => (current.length > 0 ? current : content));
                setReplyOpen(true);
            }
        }, 250);
        return () => window.clearTimeout(timer);
    }, [replyDraftKeyParts]);

    useEffect(() => {
        if (replyOpen && replyValue.length > 0) {
            replyDraft.persist({ content: replyValue });
        }
    }, [replyOpen, replyValue, replyDraft]);

    const submitReply = async () => {
        if (replySubmitting || commentPlainText(replyValue).length === 0) return;
        replyToken.current ??= crypto.randomUUID();
        setReplySubmitting(true);
        try {
            const succeeded = await onSubmitReply(thread, replyValue, replyToken.current);
            if (succeeded) {
                setReplyValue('');
                setReplyOpen(false);
                replyToken.current = null;
                replyDraft.clear();
            }
        } finally {
            setReplySubmitting(false);
        }
    };

    const root = thread.comments[0];
    const rootAuthor = root?.author?.displayName ?? t('formerMember');
    const rootSnippet = root?.deletedAt != null ? t('deletedComment') : commentPlainText(root?.content ?? '');
    const visibleCount = thread.comments.filter((comment) => comment.deletedAt == null).length;
    const resolver = resolverName(thread);
    const showReplySlot = canComment && !resolved;

    const rows = (
        <div className="grid grid-cols-[2rem_minmax(0,1fr)] gap-x-3">
            {thread.comments.map((comment, index) => {
                const highlighted = highlightedCommentId === String(comment.id);
                const deleted = comment.deletedAt != null;
                const isLast = index === thread.comments.length - 1;
                return (
                    <CommentRow
                        key={comment.id}
                        comment={comment}
                        isRoot={index === 0}
                        showRail={!isLast || showReplySlot}
                        highlighted={highlighted}
                        highlightRef={highlighted ? highlightRef : undefined}
                        canReact={canComment}
                        canDelete={
                            !deleted && (comment.author?.id === currentUser.id || canModerate)
                        }
                        canEdit={canComment && !resolved && comment.author?.id === currentUser.id}
                        canResolve={index === 0 && !resolved && canComment}
                        onDelete={onDelete}
                        onToggleReaction={onToggleReaction}
                        onCopyLink={onCopyLink}
                        onSubmitEdit={onSubmitEdit}
                        onResolve={onResolve}
                    />
                );
            })}

            {showReplySlot && (
                <>
                    <div className="flex justify-center">
                        <Avatar className="mt-0.5 size-7 shrink-0">
                            <AvatarImage src={currentUser.profilePictureUrl ?? undefined} alt="" />
                            <AvatarFallback className="text-[0.65rem]">
                                {currentUser.displayName.slice(0, 1).toUpperCase()}
                            </AvatarFallback>
                        </Avatar>
                    </div>
                    <div className="min-w-0 pb-0.5">
                        {replyOpen ? (
                            <CommentComposer
                                value={replyValue}
                                onChange={(value) => {
                                    setReplyValue(value);
                                    replyToken.current = null;
                                }}
                                onSubmit={submitReply}
                                onCancel={() => {
                                    setReplyOpen(false);
                                    setReplyValue('');
                                    replyDraft.clear();
                                }}
                                placeholder={t('replyPlaceholder')}
                                submitLabel={t('reply')}
                                submitting={replySubmitting}
                                canSubmit={commentPlainText(replyValue).length > 0}
                                autoFocus
                            />
                        ) : (
                            <button
                                type="button"
                                onClick={() => setReplyOpen(true)}
                                className="block w-full cursor-text rounded-full border border-border/70 bg-muted/40 px-3.5 py-1.5 text-left text-sm text-muted-foreground transition-colors hover:border-border hover:bg-muted/60"
                            >
                                {t('replyPlaceholder')}
                            </button>
                        )}
                    </div>
                </>
            )}
        </div>
    );

    if (!resolved) {
        return <li className="px-4 py-5 sm:px-5">{rows}</li>;
    }

    return (
        <li className="px-4 py-2.5 sm:px-5">
            <button
                type="button"
                onClick={() => setExpandedOverride((value) => !(value ?? forceExpanded))}
                aria-expanded={expanded}
                title={expanded ? t('collapseResolved') : t('expandResolved')}
                className="-mx-2 flex w-full cursor-pointer items-center gap-2.5 rounded-lg px-2 py-1.5 text-left transition-colors hover:bg-muted/50"
            >
                <CheckCircleSolidIcon className="size-5 shrink-0 text-brand" aria-hidden />
                <span className="min-w-0 flex-1 truncate text-sm text-muted-foreground">
                    <span className="font-medium text-foreground/80">{rootAuthor}</span>
                    {rootSnippet ? <span aria-hidden>{' '}</span> : null}
                    {rootSnippet}
                </span>
                <span className="shrink-0 text-xs text-muted-foreground">
                    {t('commentCount', { count: visibleCount })}
                </span>
                <ChevronDownIcon
                    className={cn(
                        'size-4 shrink-0 text-muted-foreground transition-transform duration-200 motion-reduce:transition-none',
                        expanded && 'rotate-180',
                    )}
                    aria-hidden
                />
            </button>

            {expanded && (
                <div className="pt-4">
                    {rows}
                    <div className="mt-1 flex items-center justify-between gap-3 pl-11">
                        <p className="text-xs text-muted-foreground">
                            {resolver
                                ? t('resolvedByLabel', { name: resolver })
                                : t('resolvedLabel')}
                            {thread.resolvedAt
                                ? ` · ${formatRelativeTime(thread.resolvedAt, locale, now)}`
                                : ''}
                        </p>
                        {canComment && (
                            <Button
                                type="button"
                                variant="ghost"
                                size="sm"
                                disabled={transitioning}
                                onClick={onReopen}
                                title={
                                    thread.resolvedAt
                                        ? formatUtcDateTime(thread.resolvedAt, locale)
                                        : undefined
                                }
                            >
                                {t('reopen')}
                            </Button>
                        )}
                    </div>
                </div>
            )}
        </li>
    );
}
