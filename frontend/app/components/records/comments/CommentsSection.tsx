'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import { useReducedMotion } from 'motion/react';
import { LoaderCircle } from 'lucide-react';
import { ArrowUturnLeftIcon, TrashIcon } from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';
import {
    createCommentThread,
    deleteRecordComment,
    getCommentThreads,
    replyToCommentThread,
} from '@/app/lib/api';
import type { RecordComment, RecordCommentTargetType, RecordCommentThread } from '@/app/lib/types';
import { formatDateTime } from '@/app/lib/utils';
import { toastError, toastSuccess } from '@/app/lib/toast';
import MentionEditor from '@/app/components/activity/notes/MentionEditor';
import NoteContent from '@/app/components/activity/notes/NoteContent';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Button } from '@/components/ui/button';
import {
    Dialog,
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';

type Props = {
    targetType: RecordCommentTargetType;
    targetId: number;
    currentUserId: number;
    canComment: boolean;
    canModerate: boolean;
    className?: string;
};

function tokenText(value: string): string {
    return value.replace(/\[([^\]]*)\]\((?:user|person|deal|company|note|file|task|activity):\d+\)/g, '$1').trim();
}

/**
 * Workspace-local discussion feed for one record (#906 slice 0): open threads with
 * replies, a MentionEditor composer, and redaction tombstones. Comments are
 * immutable — the only mutation besides posting is a soft redact. The section is
 * deliberately calm: no entry animations, content ordered oldest-first inside a
 * thread and newest thread first.
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
    const locale = useLocale();
    const searchParams = useSearchParams();
    const reduceMotion = useReducedMotion();

    const [threads, setThreads] = useState<RecordCommentThread[]>([]);
    const [loaded, setLoaded] = useState(false);
    const [composerValue, setComposerValue] = useState('');
    const [replyThreadId, setReplyThreadId] = useState<number | null>(null);
    const [replyValue, setReplyValue] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [pendingDelete, setPendingDelete] = useState<RecordComment | null>(null);
    const [deleting, setDeleting] = useState(false);

    const composerToken = useRef<string | null>(null);
    const replyToken = useRef<string | null>(null);
    const highlightRef = useRef<HTMLLIElement | null>(null);

    const highlightedCommentId = searchParams.get('comment');

    useEffect(() => {
        let active = true;
        getCommentThreads(targetType, targetId)
            .then((data) => {
                if (!active) return;
                setThreads(data);
                setLoaded(true);
            })
            .catch(() => {
                if (!active) return;
                setLoaded(true);
                toastError(t('loadFailed'));
            });
        return () => {
            active = false;
        };
    }, [targetType, targetId, t]);

    useEffect(() => {
        if (!loaded || !highlightedCommentId) return;
        highlightRef.current?.scrollIntoView({
            behavior: reduceMotion ? 'auto' : 'smooth',
            block: 'center',
        });
    }, [loaded, highlightedCommentId, reduceMotion]);

    const commentCount = useMemo(
        () =>
            threads.reduce(
                (sum, thread) => sum + thread.comments.filter((comment) => !comment.deleted).length,
                0,
            ),
        [threads],
    );

    const handlePost = useCallback(async () => {
        if (submitting || tokenText(composerValue).length === 0) return;
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
    }, [submitting, composerValue, targetType, targetId, t]);

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
                                      ? { ...comment, content: null, deleted: true }
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

    const isEmpty = loaded && threads.length === 0;

    return (
        <div className={cn(className)}>
            <div className="mb-3 flex h-8 items-center">
                <h2 className="px-6 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                    {t('title')}
                    {loaded ? ` · ${commentCount}` : ''}
                </h2>
            </div>

            <div className="overflow-hidden rounded-2xl bg-card ring-1 ring-border">
                {!loaded && (
                    <div className="flex items-center justify-center px-6 py-8">
                        <LoaderCircle className="size-5 animate-spin text-muted-foreground" />
                    </div>
                )}

                {isEmpty && (
                    <p className="px-6 py-6 text-sm text-muted-foreground">{t('empty')}</p>
                )}

                {loaded && threads.length > 0 && (
                    <ul className="divide-y divide-border">
                        {threads.map((thread) => (
                            <li key={thread.id} className="px-4 py-4">
                                <ul className="flex flex-col gap-3">
                                    {thread.comments.map((comment, index) => {
                                        const highlighted =
                                            highlightedCommentId === String(comment.id);
                                        const canDelete =
                                            !comment.deleted &&
                                            (comment.author.id === currentUserId || canModerate);
                                        return (
                                            <li
                                                key={comment.id}
                                                ref={highlighted ? highlightRef : undefined}
                                                className={cn(
                                                    'group flex scroll-mt-24 items-start gap-3 rounded-lg px-2 py-1.5 transition-colors duration-700',
                                                    index > 0 && 'ml-9',
                                                    highlighted && 'bg-brand-light/40',
                                                )}
                                            >
                                                <Avatar className="mt-0.5 size-7 shrink-0">
                                                    <AvatarImage
                                                        src={comment.author.profileImageUrl ?? undefined}
                                                        alt=""
                                                    />
                                                    <AvatarFallback className="text-[0.65rem]">
                                                        {comment.author.displayName.slice(0, 1).toUpperCase()}
                                                    </AvatarFallback>
                                                </Avatar>
                                                <div className="min-w-0 flex-1">
                                                    <p className="flex items-baseline gap-2 text-sm">
                                                        <span className="font-medium text-foreground">
                                                            {comment.author.displayName}
                                                        </span>
                                                        <span className="text-xs text-muted-foreground">
                                                            {formatDateTime(comment.createdAt, locale)}
                                                        </span>
                                                    </p>
                                                    {comment.deleted ? (
                                                        <p className="text-sm italic text-muted-foreground">
                                                            {t('deletedComment')}
                                                        </p>
                                                    ) : (
                                                        <NoteContent
                                                            content={comment.content ?? ''}
                                                            className="text-sm text-foreground"
                                                            block
                                                        />
                                                    )}
                                                </div>
                                                {canDelete && (
                                                    <button
                                                        type="button"
                                                        onClick={() => setPendingDelete(comment)}
                                                        className="shrink-0 cursor-pointer rounded-md p-1.5 text-muted-foreground opacity-0 transition-[color,background-color,opacity] hover:bg-destructive/10 hover:text-destructive focus-visible:opacity-100 group-hover:opacity-100"
                                                        title={t('delete')}
                                                        aria-label={t('delete')}
                                                    >
                                                        <TrashIcon className="size-4" />
                                                    </button>
                                                )}
                                            </li>
                                        );
                                    })}
                                </ul>

                                {canComment && replyThreadId !== thread.id && (
                                    <div className="mt-2 ml-11">
                                        <button
                                            type="button"
                                            onClick={() => {
                                                setReplyThreadId(thread.id);
                                                setReplyValue('');
                                                replyToken.current = null;
                                            }}
                                            className="inline-flex cursor-pointer items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                                        >
                                            <ArrowUturnLeftIcon className="size-3.5" />
                                            {t('reply')}
                                        </button>
                                    </div>
                                )}

                                {canComment && replyThreadId === thread.id && (
                                    <div className="mt-2 ml-11 flex flex-col gap-2">
                                        <MentionEditor
                                            value={replyValue}
                                            onChange={(value) => {
                                                setReplyValue(value);
                                                replyToken.current = null;
                                            }}
                                            placeholder={t('replyPlaceholder')}
                                            ariaLabel={t('reply')}
                                            autoFocus
                                            onSubmit={handleReply}
                                            className="min-h-16 rounded-xl border border-border bg-background px-3 py-2 text-sm"
                                        />
                                        <div className="flex justify-end gap-2">
                                            <Button
                                                type="button"
                                                variant="ghost"
                                                size="sm"
                                                disabled={submitting}
                                                onClick={() => {
                                                    setReplyThreadId(null);
                                                    setReplyValue('');
                                                }}
                                            >
                                                {t('cancel')}
                                            </Button>
                                            <Button
                                                type="button"
                                                variant="brand"
                                                size="sm"
                                                disabled={submitting || tokenText(replyValue).length === 0}
                                                onClick={handleReply}
                                            >
                                                {submitting ? (
                                                    <LoaderCircle className="size-4 animate-spin" />
                                                ) : (
                                                    t('reply')
                                                )}
                                            </Button>
                                        </div>
                                    </div>
                                )}
                            </li>
                        ))}
                    </ul>
                )}

                {canComment && (
                    <div className={cn('flex flex-col gap-2 p-3', !isEmpty && 'border-t border-border')}>
                        <MentionEditor
                            value={composerValue}
                            onChange={(value) => {
                                setComposerValue(value);
                                composerToken.current = null;
                            }}
                            placeholder={t('composerPlaceholder')}
                            ariaLabel={t('title')}
                            onSubmit={handlePost}
                            className="min-h-16 rounded-xl border border-border bg-background px-3 py-2 text-sm"
                        />
                        <div className="flex justify-end">
                            <Button
                                type="button"
                                variant="brand"
                                size="sm"
                                disabled={submitting || tokenText(composerValue).length === 0}
                                onClick={handlePost}
                            >
                                {submitting ? (
                                    <LoaderCircle className="size-4 animate-spin" />
                                ) : (
                                    t('post')
                                )}
                            </Button>
                        </div>
                    </div>
                )}
            </div>

            <Dialog
                open={pendingDelete != null}
                onOpenChange={(open) => {
                    if (!open && !deleting) setPendingDelete(null);
                }}
            >
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>{t('deleteTitle')}</DialogTitle>
                        <DialogDescription>{t('deleteBody')}</DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                        <DialogClose asChild>
                            <Button variant="outline" disabled={deleting}>
                                {t('cancel')}
                            </Button>
                        </DialogClose>
                        <Button
                            variant="destructive"
                            className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                            disabled={deleting}
                            onClick={confirmDelete}
                        >
                            {deleting ? (
                                <LoaderCircle className="size-4 animate-spin" />
                            ) : (
                                t('confirmDelete')
                            )}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </div>
    );
}
