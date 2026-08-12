'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { useReducedMotion } from 'motion/react';
import { ChatBubbleLeftRightIcon } from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';
import {
    addCommentReaction,
    ApiError,
    createCommentThread,
    deleteRecordComment,
    getCommentThreads,
    removeCommentReaction,
    reopenCommentThread,
    replyToCommentThread,
    resolveCommentThread,
} from '@/app/lib/api';
import type {
    RecordComment,
    RecordCommentReactionKey,
    RecordCommentTargetType,
    RecordCommentThread as RecordCommentThreadType,
} from '@/app/lib/types';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { commentPlainText } from '@/app/components/records/comments/commentText';
import CommentComposer from '@/app/components/records/comments/CommentComposer';
import CommentDeleteDialog from '@/app/components/records/comments/CommentDeleteDialog';
import CommentThread, {
    type CommentAuthorIdentity,
} from '@/app/components/records/comments/CommentThread';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';

type Props = {
    targetType: RecordCommentTargetType;
    targetId: number;
    currentUser: CommentAuthorIdentity;
    canComment: boolean;
    canModerate: boolean;
    className?: string;
};

const PAGE_SIZE = 10;
const DEEP_LINK_LIMIT = 50;

/**
 * Workspace-local discussion feed for one record (#906): the composer leads the
 * card, open threads follow newest-first on their avatar rails, and resolved
 * threads recede to collapsed summaries. Comments are immutable; the only
 * mutation besides posting is a soft redact. Deliberately calm: shape-matched
 * skeletons, state-driven motion only, one action cluster per comment.
 */
export default function CommentsSection({
    targetType,
    targetId,
    currentUser,
    canComment,
    canModerate,
    className,
}: Props) {
    const t = useTranslations('Comments');
    const searchParams = useSearchParams();
    const reduceMotion = useReducedMotion();

    const [threads, setThreads] = useState<RecordCommentThreadType[]>([]);
    const [loaded, setLoaded] = useState(false);
    const [loadError, setLoadError] = useState(false);
    const [hasMore, setHasMore] = useState(false);
    const [loadingMore, setLoadingMore] = useState(false);
    const [refreshNonce, setRefreshNonce] = useState(0);
    const [composerValue, setComposerValue] = useState('');
    const [replyThreadId, setReplyThreadId] = useState<number | null>(null);
    const [replyValue, setReplyValue] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [pendingDelete, setPendingDelete] = useState<RecordComment | null>(null);
    const [deleting, setDeleting] = useState(false);

    const composerToken = useRef<string | null>(null);
    const replyToken = useRef<string | null>(null);
    const highlightRef = useRef<HTMLDivElement | null>(null);
    const highlightScrolled = useRef(false);
    const fetchGeneration = useRef(0);
    const reactionBusyIds = useRef<Set<number>>(new Set());

    const highlightedCommentId = searchParams.get('comment');
    const initialLimit = highlightedCommentId ? DEEP_LINK_LIMIT : PAGE_SIZE;

    useEffect(() => {
        let active = true;
        fetchGeneration.current += 1;
        getCommentThreads(targetType, targetId, { limit: initialLimit, state: 'all' })
            .then((data) => {
                if (!active) return;
                setThreads(data);
                setHasMore(data.length === initialLimit);
                setLoadError(false);
                setLoaded(true);
            })
            .catch(() => {
                if (!active) return;
                setLoadError(true);
                setLoaded(true);
            });
        return () => {
            active = false;
        };
    }, [targetType, targetId, initialLimit, refreshNonce]);

    useEffect(() => {
        if (!loaded || !highlightedCommentId || highlightScrolled.current) return;
        if (!highlightRef.current) return;
        highlightScrolled.current = true;
        highlightRef.current.scrollIntoView({
            behavior: reduceMotion ? 'auto' : 'smooth',
            block: 'center',
        });
    }, [loaded, threads, highlightedCommentId, reduceMotion]);

    const commentCount = useMemo(
        () =>
            threads.reduce(
                (sum, thread) =>
                    sum + thread.comments.filter((comment) => comment.deletedAt == null).length,
                0,
            ),
        [threads],
    );

    const highlightedThreadId = useMemo(() => {
        if (!highlightedCommentId) return null;
        return (
            threads.find((thread) =>
                thread.comments.some((comment) => String(comment.id) === highlightedCommentId),
            )?.id ?? null
        );
    }, [threads, highlightedCommentId]);

    const handleLoadMore = useCallback(async () => {
        if (loadingMore) return;
        const generation = fetchGeneration.current;
        setLoadingMore(true);
        try {
            const data = await getCommentThreads(targetType, targetId, {
                limit: PAGE_SIZE,
                offset: threads.length,
                state: 'all',
            });
            if (generation !== fetchGeneration.current) return;
            setThreads((prev) => [
                ...prev,
                ...data.filter((thread) => !prev.some((existing) => existing.id === thread.id)),
            ]);
            setHasMore(data.length === PAGE_SIZE);
        } catch {
            toastError(t('loadFailed'));
        } finally {
            setLoadingMore(false);
        }
    }, [loadingMore, targetType, targetId, threads.length, t]);

    const handlePost = useCallback(async () => {
        if (submitting || !loaded || commentPlainText(composerValue).length === 0) return;
        composerToken.current ??= crypto.randomUUID();
        setSubmitting(true);
        try {
            const created = await createCommentThread({
                targetType,
                targetId,
                content: composerValue,
                clientToken: composerToken.current,
            });
            setThreads((prev) => [created, ...prev.filter((thread) => thread.id !== created.id)]);
            setComposerValue('');
            composerToken.current = null;
            toastSuccess(t('posted'));
        } catch {
            toastError(t('postFailed'));
        } finally {
            setSubmitting(false);
        }
    }, [submitting, loaded, composerValue, targetType, targetId, t]);

    const handleReply = useCallback(async () => {
        if (submitting || replyThreadId == null || commentPlainText(replyValue).length === 0) return;
        replyToken.current ??= crypto.randomUUID();
        setSubmitting(true);
        try {
            const created = await replyToCommentThread(replyThreadId, {
                content: replyValue,
                clientToken: replyToken.current,
            });
            setThreads((prev) =>
                prev.map((thread) =>
                    thread.id === replyThreadId
                        ? {
                              ...thread,
                              comments: [
                                  ...thread.comments.filter((comment) => comment.id !== created.id),
                                  created,
                              ],
                          }
                        : thread,
                ),
            );
            setReplyValue('');
            setReplyThreadId(null);
            replyToken.current = null;
            toastSuccess(t('posted'));
        } catch (error) {
            if (error instanceof ApiError && error.status === 409) {
                toastError(t('conflict'));
                setReplyThreadId(null);
                setReplyValue('');
                replyToken.current = null;
                setLoaded(false);
                setRefreshNonce((nonce) => nonce + 1);
            } else {
                toastError(t('postFailed'));
            }
        } finally {
            setSubmitting(false);
        }
    }, [submitting, replyThreadId, replyValue, t]);

    const handleStateTransition = useCallback(
        async (thread: RecordCommentThreadType, action: 'resolve' | 'reopen') => {
            if (submitting) return;
            setSubmitting(true);
            try {
                const updated =
                    action === 'resolve'
                        ? await resolveCommentThread(thread.id, thread.version)
                        : await reopenCommentThread(thread.id, thread.version);
                setThreads((prev) =>
                    prev.map((existing) =>
                        existing.id === thread.id
                            ? {
                                  ...updated,
                                  comments:
                                      updated.comments.length > 0
                                          ? updated.comments
                                          : existing.comments,
                              }
                            : existing,
                    ),
                );
                toastSuccess(t(action === 'resolve' ? 'resolvedToast' : 'reopenedToast'));
            } catch (error) {
                if (error instanceof ApiError && error.status === 409) {
                    toastError(t('conflict'));
                    setLoaded(false);
                    setRefreshNonce((nonce) => nonce + 1);
                } else {
                    toastError(t('actionFailed'));
                }
            } finally {
                setSubmitting(false);
            }
        },
        [submitting, t],
    );

    const handleToggleReaction = useCallback(
        async (comment: RecordComment, reaction: RecordCommentReactionKey) => {
            if (reactionBusyIds.current.has(comment.id)) return;
            reactionBusyIds.current.add(comment.id);
            const mine = comment.reactions?.some(
                (summary) => summary.reaction === reaction && summary.reactedByMe,
            );
            try {
                const summary = mine
                    ? await removeCommentReaction(comment.id, reaction)
                    : await addCommentReaction(comment.id, reaction);
                setThreads((prev) =>
                    prev.map((thread) =>
                        thread.id === comment.threadId
                            ? {
                                  ...thread,
                                  comments: thread.comments.map((existing) =>
                                      existing.id === comment.id
                                          ? { ...existing, reactions: summary }
                                          : existing,
                                  ),
                              }
                            : thread,
                    ),
                );
            } catch {
                toastError(t('reactionFailed'));
            } finally {
                reactionBusyIds.current.delete(comment.id);
            }
        },
        [t],
    );

    const confirmDelete = useCallback(async () => {
        if (!pendingDelete || deleting) return;
        setDeleting(true);
        try {
            await deleteRecordComment(pendingDelete.id);
            setThreads((prev) =>
                prev.map((thread) =>
                    thread.id === pendingDelete.threadId
                        ? {
                              ...thread,
                              comments: thread.comments.map((comment) =>
                                  comment.id === pendingDelete.id
                                      ? { ...comment, content: null, deletedAt: new Date().toISOString() }
                                      : comment,
                              ),
                          }
                        : thread,
                ),
            );
            setPendingDelete(null);
            toastSuccess(t('deleted'));
        } catch {
            toastError(t('deleteFailed'));
        } finally {
            setDeleting(false);
        }
    }, [pendingDelete, deleting, t]);

    const isEmpty = loaded && !loadError && threads.length === 0;

    return (
        <div className={cn(className)}>
            <div className="mb-3 flex h-8 items-center">
                <h2 className="px-6 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                    {t('title')}
                    {loaded && !loadError ? ` · ${commentCount}` : ''}
                </h2>
            </div>

            <div className="overflow-hidden rounded-2xl bg-card ring-1 ring-border">
                {canComment && (
                    <div className="grid grid-cols-[2rem_minmax(0,1fr)] gap-x-3 px-4 pb-4 pt-4 sm:px-5">
                        <Avatar className="mt-0.5 size-8 shrink-0">
                            <AvatarImage src={currentUser.profilePictureUrl ?? undefined} alt="" />
                            <AvatarFallback className="text-xs">
                                {currentUser.displayName.slice(0, 1).toUpperCase()}
                            </AvatarFallback>
                        </Avatar>
                        <CommentComposer
                            value={composerValue}
                            onChange={(value) => {
                                setComposerValue(value);
                                composerToken.current = null;
                            }}
                            onSubmit={handlePost}
                            placeholder={t('composerPlaceholder')}
                            submitLabel={t('post')}
                            submitting={submitting}
                            disabled={!loaded}
                            canSubmit={commentPlainText(composerValue).length > 0}
                        />
                    </div>
                )}

                {!loaded && (
                    <ul
                        className={cn(
                            'flex flex-col gap-5 px-4 pb-5 sm:px-5',
                            canComment && 'border-t border-border pt-5',
                        )}
                    >
                        {[0, 1].map((row) => (
                            <li key={row} className="grid grid-cols-[2rem_minmax(0,1fr)] gap-x-3">
                                <Skeleton className="size-8 rounded-full" />
                                <div className="flex min-w-0 flex-col gap-2 pt-1">
                                    <Skeleton className="h-3.5 w-40" />
                                    <Skeleton className="h-4 w-3/4" />
                                </div>
                            </li>
                        ))}
                    </ul>
                )}

                {loaded && loadError && (
                    <div
                        className={cn(
                            'flex items-center justify-between gap-3 px-5 py-5',
                            canComment && 'border-t border-border',
                        )}
                    >
                        <p className="text-sm text-muted-foreground">{t('loadFailed')}</p>
                        <Button
                            type="button"
                            variant="outline"
                            size="sm"
                            onClick={() => {
                                setLoaded(false);
                                setRefreshNonce((nonce) => nonce + 1);
                            }}
                        >
                            {t('retry')}
                        </Button>
                    </div>
                )}

                {isEmpty && (
                    <div
                        className={cn(
                            'flex flex-col items-center gap-2 px-6 pb-8 text-center',
                            canComment ? 'border-t border-border pt-6' : 'pt-8',
                        )}
                    >
                        <span className="grid size-10 place-items-center rounded-full bg-muted text-muted-foreground">
                            <ChatBubbleLeftRightIcon className="size-5" aria-hidden />
                        </span>
                        <p className="text-sm font-medium text-foreground">{t('emptyTitle')}</p>
                        <p className="max-w-sm text-sm text-muted-foreground">{t('empty')}</p>
                    </div>
                )}

                {loaded &&
                    !loadError &&
                    highlightedCommentId != null &&
                    highlightedThreadId == null && (
                        <p
                            className={cn(
                                'px-5 py-3 text-xs text-muted-foreground',
                                canComment && 'border-t border-border',
                            )}
                        >
                            {t('deepLinkGone')}
                        </p>
                    )}

                {loaded && !loadError && threads.length > 0 && (
                    <ul
                        className={cn(
                            'divide-y divide-border',
                            canComment && 'border-t border-border',
                        )}
                    >
                        {threads.map((thread) => (
                            <CommentThread
                                key={thread.id}
                                thread={thread}
                                currentUser={currentUser}
                                canComment={canComment}
                                canModerate={canModerate}
                                highlightedCommentId={highlightedCommentId}
                                highlightRef={highlightRef}
                                forceExpanded={thread.id === highlightedThreadId}
                                replyOpen={replyThreadId === thread.id}
                                replyValue={replyValue}
                                replyCanSubmit={commentPlainText(replyValue).length > 0}
                                submitting={submitting}
                                onOpenReply={() => {
                                    setReplyThreadId(thread.id);
                                    setReplyValue('');
                                    replyToken.current = null;
                                }}
                                onCancelReply={() => {
                                    setReplyThreadId(null);
                                    setReplyValue('');
                                }}
                                onReplyChange={(value) => {
                                    setReplyValue(value);
                                    replyToken.current = null;
                                }}
                                onReplySubmit={handleReply}
                                onDelete={setPendingDelete}
                                onToggleReaction={handleToggleReaction}
                                onResolve={() => handleStateTransition(thread, 'resolve')}
                                onReopen={() => handleStateTransition(thread, 'reopen')}
                            />
                        ))}
                    </ul>
                )}

                {loaded && !loadError && hasMore && (
                    <div className="flex justify-center border-t border-border py-1.5">
                        <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            className="text-muted-foreground"
                            disabled={loadingMore}
                            onClick={handleLoadMore}
                        >
                            {loadingMore ? t('loadingMore') : t('loadMore')}
                        </Button>
                    </div>
                )}
            </div>

            <CommentDeleteDialog
                open={pendingDelete != null}
                deleting={deleting}
                onCancel={() => setPendingDelete(null)}
                onConfirm={confirmDelete}
            />
        </div>
    );
}
