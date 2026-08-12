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
    editRecordComment,
    getCommentThread,
    getCommentThreads,
    removeCommentReaction,
    reopenCommentThread,
    replyToCommentThread,
    resolveCommentThread,
    uploadAttachment,
} from '@/app/lib/api';
import { normalizeNoteImageSource } from '@/app/components/activity/notes/editor/noteImageSource';
import type { DraftKeyParts } from '@/app/lib/formDrafts';
import { useWorkspace } from '@/app/hooks/useWorkspace';
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
    const { activeWorkspaceId } = useWorkspace();

    const [threads, setThreads] = useState<RecordCommentThreadType[]>([]);
    const [loaded, setLoaded] = useState(false);
    const [loadError, setLoadError] = useState(false);
    const [hasMore, setHasMore] = useState(false);
    const [loadingMore, setLoadingMore] = useState(false);
    const [refreshNonce, setRefreshNonce] = useState(0);
    const [composerValue, setComposerValue] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [pendingDelete, setPendingDelete] = useState<RecordComment | null>(null);
    const [deleting, setDeleting] = useState(false);

    const composerToken = useRef<string | null>(null);
    const highlightRef = useRef<HTMLDivElement | null>(null);
    const highlightScrolled = useRef(false);
    const fetchGeneration = useRef(0);
    const reactionBusyIds = useRef<Set<number>>(new Set());

    const highlightedCommentId = searchParams.get('comment');
    const linkedThreadParam = searchParams.get('thread');
    const linkedThreadFetched = useRef(false);
    const appendedThreadId = useRef<number | null>(null);
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
        if (!loaded || loadError || linkedThreadFetched.current) return;
        linkedThreadFetched.current = true;
        const linkedThreadId = linkedThreadParam ? Number(linkedThreadParam) : null;
        if (!linkedThreadId || Number.isNaN(linkedThreadId)) return;
        if (threads.some((thread) => thread.id === linkedThreadId)) return;
        getCommentThread(linkedThreadId)
            .then((thread) => {
                if (thread.targetType !== targetType || thread.targetId !== targetId) return;
                appendedThreadId.current = thread.id;
                setThreads((prev) =>
                    prev.some((existing) => existing.id === thread.id) ? prev : [...prev, thread],
                );
            })
            .catch(() => undefined);
    }, [loaded, loadError, threads, linkedThreadParam, targetType, targetId]);

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
            const pagedCount = threads.filter(
                (thread) => thread.id !== appendedThreadId.current,
            ).length;
            const data = await getCommentThreads(targetType, targetId, {
                limit: PAGE_SIZE,
                offset: pagedCount,
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

    const submitReply = useCallback(
        async (
            thread: RecordCommentThreadType,
            content: string,
            clientToken: string,
        ): Promise<boolean> => {
            try {
                const created = await replyToCommentThread(thread.id, { content, clientToken });
                setThreads((prev) =>
                    prev.map((existing) =>
                        existing.id === thread.id
                            ? {
                                  ...existing,
                                  comments: [
                                      ...existing.comments.filter(
                                          (comment) => comment.id !== created.id,
                                      ),
                                      created,
                                  ],
                              }
                            : existing,
                    ),
                );
                toastSuccess(t('posted'));
                return true;
            } catch (error) {
                if (error instanceof ApiError && error.status === 409) {
                    toastError(t('conflict'));
                    setLoaded(false);
                    setRefreshNonce((nonce) => nonce + 1);
                } else {
                    toastError(t('postFailed'));
                }
                return false;
            }
        },
        [t],
    );

    const submitEdit = useCallback(
        async (comment: RecordComment, content: string): Promise<boolean> => {
            try {
                const updated = await editRecordComment(comment.id, content);
                setThreads((prev) =>
                    prev.map((thread) =>
                        thread.id === comment.threadId
                            ? {
                                  ...thread,
                                  comments: thread.comments.map((existing) =>
                                      existing.id === comment.id ? updated : existing,
                                  ),
                              }
                            : thread,
                    ),
                );
                toastSuccess(t('editSaved'));
                return true;
            } catch (error) {
                if (error instanceof ApiError && error.status === 409) {
                    toastError(t('conflict'));
                    setLoaded(false);
                    setRefreshNonce((nonce) => nonce + 1);
                    return true;
                }
                toastError(t('editFailed'));
                return false;
            }
        },
        [t],
    );

    const attachImage = useCallback(
        async (file: File): Promise<string | null> => {
            try {
                const attachment = await uploadAttachment(targetType, targetId, file);
                const source = normalizeNoteImageSource(attachment.url);
                if (!source) {
                    toastError(t('imageUploadFailed'));
                    return null;
                }
                return source;
            } catch {
                toastError(t('imageUploadFailed'));
                return null;
            }
        },
        [targetType, targetId, t],
    );

    const copyCommentLink = useCallback(
        (comment: RecordComment) => {
            const collection =
                targetType === 'person'
                    ? 'contacts'
                    : targetType === 'company'
                      ? 'companies'
                      : 'deals';
            const url = `${window.location.origin}/records/${collection}/${targetId}?comment=${comment.id}&thread=${comment.threadId}`;
            try {
                navigator.clipboard
                    .writeText(url)
                    .then(() => toastSuccess(t('linkCopied')))
                    .catch(() => toastError(t('copyFailed')));
            } catch {
                toastError(t('copyFailed'));
            }
        },
        [targetType, targetId, t],
    );

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

    const composerDraftKeyParts = useMemo<DraftKeyParts>(
        () => ({
            userId: currentUser.id,
            workspaceId: activeWorkspaceId,
            formType: 'comment',
            scope: `${targetType}:${targetId}`,
        }),
        [currentUser.id, activeWorkspaceId, targetType, targetId],
    );

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
                            draftKeyParts={composerDraftKeyParts}
                            onAttachImage={attachImage}
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
                                activeWorkspaceId={activeWorkspaceId}
                                canComment={canComment}
                                canModerate={canModerate}
                                highlightedCommentId={highlightedCommentId}
                                highlightRef={highlightRef}
                                forceExpanded={thread.id === highlightedThreadId}
                                transitioning={submitting}
                                onSubmitReply={submitReply}
                                onSubmitEdit={submitEdit}
                                onAttachImage={attachImage}
                                onCopyLink={copyCommentLink}
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
