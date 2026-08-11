'use client';

import type { Ref } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { ArrowUturnLeftIcon, CheckCircleIcon } from '@heroicons/react/24/outline';

import type { RecordComment, RecordCommentThread } from '@/app/lib/types';
import { formatDateTime } from '@/app/lib/utils';
import CommentComposer from '@/app/components/records/comments/CommentComposer';
import CommentRow from '@/app/components/records/comments/CommentRow';

type Props = {
    thread: RecordCommentThread;
    currentUserId: number;
    canComment: boolean;
    canModerate: boolean;
    highlightedCommentId: string | null;
    highlightRef: Ref<HTMLLIElement>;
    replyOpen: boolean;
    replyValue: string;
    replyCanSubmit: boolean;
    submitting: boolean;
    onOpenReply: () => void;
    onCancelReply: () => void;
    onReplyChange: (value: string) => void;
    onReplySubmit: () => void;
    onDelete: (comment: RecordComment) => void;
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
 * One thread inside the comments feed: state header for resolved threads, the
 * comment rows, and the reply / resolve / reopen affordances.
 */
export default function CommentThreadCard({
    thread,
    currentUserId,
    canComment,
    canModerate,
    highlightedCommentId,
    highlightRef,
    replyOpen,
    replyValue,
    replyCanSubmit,
    submitting,
    onOpenReply,
    onCancelReply,
    onReplyChange,
    onReplySubmit,
    onDelete,
    onResolve,
    onReopen,
}: Props) {
    const t = useTranslations('Comments');
    const locale = useLocale();
    const resolved = thread.state === 'resolved';
    const resolver = resolverName(thread);

    return (
        <li className="px-4 py-4">
            {resolved && (
                <p className="mb-2 flex items-center gap-1.5 px-2 text-xs font-medium text-muted-foreground">
                    <CheckCircleIcon className="size-4 text-brand" />
                    {resolver ? t('resolvedByLabel', { name: resolver }) : t('resolvedLabel')}
                    {thread.resolvedAt ? ` · ${formatDateTime(thread.resolvedAt, locale)}` : ''}
                </p>
            )}

            <ul className="flex flex-col gap-3">
                {thread.comments.map((comment, index) => {
                    const highlighted = highlightedCommentId === String(comment.id);
                    const deleted = comment.deletedAt != null;
                    return (
                        <CommentRow
                            key={comment.id}
                            comment={comment}
                            indented={index > 0}
                            highlighted={highlighted}
                            highlightRef={highlighted ? highlightRef : undefined}
                            canDelete={
                                !deleted &&
                                (comment.author?.id === currentUserId || canModerate)
                            }
                            onDelete={onDelete}
                        />
                    );
                })}
            </ul>

            {canComment && !replyOpen && (
                <div className="mt-2 ml-11 flex items-center gap-1">
                    {!resolved && (
                        <button
                            type="button"
                            onClick={onOpenReply}
                            className="inline-flex cursor-pointer items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                        >
                            <ArrowUturnLeftIcon className="size-3.5" />
                            {t('reply')}
                        </button>
                    )}
                    <button
                        type="button"
                        onClick={resolved ? onReopen : onResolve}
                        disabled={submitting}
                        className="inline-flex cursor-pointer items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground disabled:pointer-events-none disabled:opacity-50"
                    >
                        <CheckCircleIcon className="size-3.5" />
                        {resolved ? t('reopen') : t('resolve')}
                    </button>
                </div>
            )}

            {canComment && replyOpen && (
                <div className="mt-2 ml-11">
                    <CommentComposer
                        value={replyValue}
                        onChange={onReplyChange}
                        onSubmit={onReplySubmit}
                        onCancel={onCancelReply}
                        placeholder={t('replyPlaceholder')}
                        submitLabel={t('reply')}
                        submitting={submitting}
                        canSubmit={replyCanSubmit}
                        autoFocus
                    />
                </div>
            )}
        </li>
    );
}
