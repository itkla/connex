'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { useReducedMotion } from 'motion/react';
import { LoaderCircle } from 'lucide-react';

import { cn } from '@/lib/utils';
import {
    ApiError,
    createCommentThread,
    deleteRecordComment,
    getCommentThreads,
    reopenCommentThread,
    replyToCommentThread,
    resolveCommentThread,
} from '@/app/lib/api';
import type { RecordComment, RecordCommentTargetType, RecordCommentThread } from '@/app/lib/types';
import { toastError, toastSuccess } from '@/app/lib/toast';
import CommentComposer from '@/app/components/records/comments/CommentComposer';
import CommentDeleteDialog from '@/app/components/records/comments/CommentDeleteDialog';
import CommentThreadCard from '@/app/components/records/comments/CommentThreadCard';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';

type Props = {
    targetType: RecordCommentTargetType;
    targetId: number;
    currentUserId: number;
    canComment: boolean;
    canModerate: boolean;
    className?: string;
};

const PAGE_SIZE = 10;
const DEEP_LINK_LIMIT = 50;

function tokenText(value: string): string {
    return value.replace(/\[([^\]]*)\]\((?:user|person|deal|company|note|file|task|activity):\d+\)/g, '$1').trim();
}

/**
 * Workspace-local discussion feed for one record (#906 slice 0): open threads with
 * replies, a MentionEditor composer, and redaction tombstones. Comments are
 * immutable — the only mutation besides posting is a soft redact. The section is
 * deliberately calm: shape-matched skeletons while loading, no entry animations,
 * comments oldest-first inside a thread and newest thread first.
 */
export default function CommentsSection({
    targetType,
    targetId,
    currentUserId,
    canComment,
    canModerate,
    className,
}: Props) {
    const t = useTranslations('Comments');
    const searchParams = useSearchParams();
    const reduceMotion = useReducedMotion();

    const [threads, setThreads] = useState<RecordCommentThread[]>([]);
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
    const highlightRef = useRef<HTMLLIElement | null>(null);
    const highlightScrolled = useRef(false);

    const highlightedCommentId = searchParams.get('comment');
    const initialLimit = highlightedCommentId ? DEEP_LINK_LIMIT : PAGE_SIZE;
    const [showResolved, setShowResolved] = useState(false);
    const stateFilter = highlightedCommentId || showResolved ? 'all' : 'open';

    useEffect(() => {
        let active = true;
        getCommentThreads(targetType, targetId, { limit: initialLimit, state: stateFilter })
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
    }, [targetType, targetId, initialLimit, stateFilter, refreshNonce]);

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

    const handleLoadMore = useCallback(async () => {
        if (loadingMore) return;
        setLoadingMore(true);
        try {
            const data = await getCommentThreads(targetType, targetId, {
                limit: PAGE_SIZE,
                offset: threads.length,
                state: stateFilter,
            });
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
    }, [loadingMore, targetType, targetId, threads.length, stateFilter, t]);

    const handleStateTransition = useCallback(
        async (thread: RecordCommentThread, action: 'resolve' | 'reopen') => {
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
                                  comments: updated.comments.length > 0
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

    const handlePost = useCallback(async () => {
        if (submitting || !loaded || tokenText(composerValue).length === 0) return;
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
        if (submitting || replyThreadId == null || tokenText(replyValue).length === 0) return;
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
        } catch {
            toastError(t('postFailed'));
        } finally {
            setSubmitting(false);
        }
    }, [submitting, replyThreadId, replyValue, t]);

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
            <div className="mb-3 flex h-8 items-center justify-between">
                <h2 className="px-6 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                    {t('title')}
                    {loaded && !loadError ? ` · ${commentCount}` : ''}
                </h2>
                {!highlightedCommentId && (
                    <button
                        type="button"
                        onClick={() => {
                            setShowResolved((value) => !value);
                            setLoaded(false);
                        }}
                        className="cursor-pointer rounded-md px-2 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                    >
                        {showResolved ? t('hideResolved') : t('showResolved')}
                    </button>
                )}
            </div>

            <div className="overflow-hidden rounded-2xl bg-card ring-1 ring-border">
                {!loaded && (
                    <ul className="flex flex-col gap-4 px-6 py-5">
                        {[0, 1].map((row) => (
                            <li key={row} className="flex items-start gap-3">
                                <Skeleton className="size-7 shrink-0 rounded-full" />
                                <div className="flex min-w-0 flex-1 flex-col gap-2">
                                    <Skeleton className="h-3.5 w-40" />
                                    <Skeleton className="h-4 w-3/4" />
                                </div>
                            </li>
                        ))}
                    </ul>
                )}

                {loaded && loadError && (
                    <div className="flex items-center justify-between gap-3 px-6 py-5">
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
                    <p className="px-6 py-6 text-sm text-muted-foreground">{t('empty')}</p>
                )}

                {loaded &&
                    !loadError &&
                    highlightedCommentId != null &&
                    !threads.some((thread) =>
                        thread.comments.some(
                            (comment) => String(comment.id) === highlightedCommentId,
                        ),
                    ) && (
                        <p className="border-b border-border px-6 py-3 text-xs text-muted-foreground">
                            {t('deepLinkGone')}
                        </p>
                    )}

                {loaded && !loadError && threads.length > 0 && (
                    <ul className="divide-y divide-border">
                        {threads.map((thread) => (
                            <CommentThreadCard
                                key={thread.id}
                                thread={thread}
                                currentUserId={currentUserId}
                                canComment={canComment}
                                canModerate={canModerate}
                                highlightedCommentId={highlightedCommentId}
                                highlightRef={highlightRef}
                                replyOpen={replyThreadId === thread.id}
                                replyValue={replyValue}
                                replyCanSubmit={tokenText(replyValue).length > 0}
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
                                onResolve={() => handleStateTransition(thread, 'resolve')}
                                onReopen={() => handleStateTransition(thread, 'reopen')}
                            />
                        ))}
                    </ul>
                )}

                {loaded && !loadError && hasMore && (
                    <div className="border-t border-border px-4 py-2">
                        <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            className="w-full text-muted-foreground"
                            disabled={loadingMore}
                            onClick={handleLoadMore}
                        >
                            {loadingMore ? (
                                <LoaderCircle className="size-4 animate-spin" />
                            ) : (
                                t('loadMore')
                            )}
                        </Button>
                    </div>
                )}

                {canComment && (
                    <div className={cn('p-3', !isEmpty && 'border-t border-border')}>
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
                            canSubmit={tokenText(composerValue).length > 0}
                        />
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
