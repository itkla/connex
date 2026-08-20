'use client';

import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { usePathname, type ReadonlyURLSearchParams, useRouter, useSearchParams } from 'next/navigation';
import { useReducedMotion } from 'motion/react';
import { useLocale, useTranslations } from 'next-intl';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { CheckIcon, EllipsisVerticalIcon, PencilIcon, TrashIcon, UserIcon } from '@heroicons/react/24/outline';

import { type Contact, type ContactLifecycleStage, type Deal, type UserReference } from '@/app/lib/types';
import { formatShortDate } from '@/app/lib/utils';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { IconButton } from '@/components/ui/icon-button';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { deleteActivity, deleteNote, deleteTask } from '@/app/lib/api';
import { ACTIVITY_URL_KEY, COMMENT_URL_KEY, NOTE_URL_KEY, TASK_URL_KEY } from '@/app/hooks/listStateUrl';
import { type TimelineEntry } from '@/app/components/me/timelineEntries';
import EditTaskSheet from '@/app/components/activity/tasks/EditTaskSheet';
import EditActivitySheet from '@/app/components/activity/activities/EditActivitySheet';
import NoteDialog from '@/app/components/activity/notes/NoteDialog';
import NoteContent from '@/app/components/activity/notes/NoteContent';
import ProviderCaptureEvidence from '@/app/components/activity/ProviderCaptureEvidence';
import { isProviderOwnedActivity } from '@/app/lib/connectedCapture';
import {
    useContactTargetSearch,
    useDealTargetSearch,
} from '@/app/hooks/useRecordTargetSearch';

const CHIP_CLASS: Record<TimelineEntry['kind'], string> = {
    task: 'bg-brand-light text-brand-dark',
    activity: 'bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900',
    note: 'bg-neutral-200 text-neutral-700 dark:bg-neutral-800 dark:text-neutral-200',
    lifecycle: 'bg-muted text-muted-foreground',
    comment: 'bg-secondary text-secondary-foreground',
    campaign: 'bg-accent text-accent-foreground',
};

const CHIP_LABEL_KEY: Record<
    TimelineEntry['kind'],
    'chipTask' | 'chipActivity' | 'chipNote' | 'chipLifecycle' | 'chipComment' | 'chipCampaign'
> = {
    task: 'chipTask',
    activity: 'chipActivity',
    note: 'chipNote',
    lifecycle: 'chipLifecycle',
    comment: 'chipComment',
    campaign: 'chipCampaign',
};

/**
 * The delivery outcomes a campaign touch reports. Anything else the server adds later renders as
 * the touch without an outcome line rather than as a raw status token.
 */
const CAMPAIGN_STATUS_KEY: Record<string, string> = {
    pending: 'campaignStatusPending',
    dispatching: 'campaignStatusDispatching',
    dispatched: 'campaignStatusDispatched',
    delivered: 'campaignStatusDelivered',
    bounced: 'campaignStatusBounced',
    complained: 'campaignStatusComplained',
    failed: 'campaignStatusFailed',
    skipped: 'campaignStatusSkipped',
};

/** Delivery channels a campaign touch names; an unrecognized one renders its own value. */
const CAMPAIGN_CHANNEL_KEY: Record<string, string> = {
    email: 'campaignChannelEmail',
    sms: 'campaignChannelSms',
};

/**
 * The deep link that addresses one timeline entry, or null for entries no producer links to.
 *
 * Task, activity, and note notifications all land on a record page carrying the canonical param key
 * their standalone browser reads, so a row highlights itself by comparing the arriving value with its
 * own id.
 *
 * A comment row deliberately has none. `?comment=` belongs to `CommentsSection`, which owns the
 * thread the comment lives in and scrolls to it; a second claimant would fight it for the scroll.
 */
function entryDeepLink(entry: TimelineEntry): { key: string; id: number } | null {
    if (entry.kind === 'task') return { key: TASK_URL_KEY, id: entry.task.id };
    if (entry.kind === 'activity') return { key: ACTIVITY_URL_KEY, id: entry.activity.id };
    if (entry.kind === 'note') return { key: NOTE_URL_KEY, id: entry.note.id };
    return null;
}

/**
 * The link from a timeline comment row to that comment inside the record's discussion. It keeps
 * every other list-state param the page is already carrying and only sets its own key, the same
 * contract `listStateUrl`'s writers follow.
 */
function commentThreadHref(
    pathname: string,
    searchParams: ReadonlyURLSearchParams,
    commentId: number,
): string {
    const params = new URLSearchParams(searchParams.toString());
    params.set(COMMENT_URL_KEY, String(commentId));
    return `${pathname}?${params.toString()}`;
}

function entryDate(entry: TimelineEntry, locale: string): string {
    return entry.sortAt
        ? formatShortDate(new Date(entry.sortAt).toISOString(), locale)
        : '';
}

function lifecycleStageLabel(
    stage: ContactLifecycleStage | null | undefined,
    tl: (key: string) => string,
): string {
    return stage == null ? tl('stage.none') : tl(`stage.${stage}`);
}

export default function TimelineRow({
    entry,
    author,
    persons,
    deals,
    currentUserId,
    companyId,
    originWorkspaceId,
}: {
    entry: TimelineEntry;
    author?: UserReference;
    persons: Contact[];
    deals: Deal[];
    currentUserId?: number;
    companyId: number | null;
    originWorkspaceId: number | null;
}) {
    const t = useTranslations('MeTimeline');
    const tl = useTranslations('ContactLifecycle');
    const locale = useLocale();
    const router = useRouter();
    const pathname = usePathname();
    const [editOpen, setEditOpen] = useState(false);
    const rowRef = useRef<HTMLLIElement>(null);
    const searchParams = useSearchParams();
    const reduceMotion = useReducedMotion();
    const deepLink = entryDeepLink(entry);
    const isHighlighted = deepLink !== null && searchParams.get(deepLink.key) === String(deepLink.id);
    const readOnlyEntry =
        entry.kind === 'lifecycle' || entry.kind === 'comment' || entry.kind === 'campaign';
    const noteOpen = editOpen && entry.kind === 'note';
    const personSearch = useContactTargetSearch(
        noteOpen,
        entry.kind === 'note' ? [entry.note.person] : [],
        persons,
    );
    const dealSearch = useDealTargetSearch(
        noteOpen,
        entry.kind === 'note' ? [entry.note.deal] : [],
        deals,
    );

    useEffect(() => {
        if (isHighlighted) {
            rowRef.current?.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'center' });
        }
    }, [isHighlighted, reduceMotion]);

    const handleDelete = async () => {
        try {
            if (entry.kind === 'task') {
                await deleteTask(entry.task.id);
                toastSuccess(t('taskDeleted'));
            } else if (entry.kind === 'activity') {
                await deleteActivity(entry.activity.id);
                toastSuccess(t('activityDeleted'));
            } else if (entry.kind === 'note') {
                await deleteNote(entry.note.id);
                toastSuccess(t('noteDeleted'));
            }
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('deleteFailed'));
        }
    };

    const date = entryDate(entry, locale);
    const chipLabel = t(CHIP_LABEL_KEY[entry.kind]);
    const commentAuthor = entry.kind === 'comment' ? entry.comment.author : null;
    const avatarUrl = commentAuthor?.profilePictureUrl ?? author?.profilePictureUrl;
    const avatarName = commentAuthor?.displayName ?? author?.displayName ?? author?.username ?? '';

    let title: React.ReactNode;
    let subtitle: React.ReactNode = null;

    if (entry.kind === 'task') {
        const { task } = entry;
        title = (
            <p className={`text-sm ${task.completed ? 'text-muted-foreground line-through' : 'text-foreground'}`}>
                <NoteContent content={task.description} references={task.references} />
            </p>
        );
        if (task.completed) {
            subtitle = (
                <span className="flex items-center gap-2">
                    <CheckIcon className="h-4 w-4 text-muted-foreground" />
                    <p className="text-xs text-muted-foreground">{t('completed')}</p>
                </span>
            );
        } else if (task.dueDate) {
            subtitle = (
                <p className="mt-0.5 text-xs text-muted-foreground">
                    {t('due', { date: formatShortDate(task.dueDate, locale) })}
                </p>
            );
        }
    } else if (entry.kind === 'activity') {
        const { activity } = entry;
        title = isProviderOwnedActivity(activity) ? (
            <Link
                href={`/activity/activities/${activity.id}`}
                className="text-sm text-foreground underline-offset-2 hover:underline"
            >
                {activity.subject}
            </Link>
        ) : (
            <p className="text-sm text-foreground">{activity.subject}</p>
        );
        subtitle = (
            <div>
                <div className="mt-0.5 flex min-w-0 items-center gap-2">
                    <span className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
                        {activity.type}
                    </span>
                    {activity.notes ? (
                        <span className="truncate text-xs text-muted-foreground">
                            · <NoteContent content={activity.notes} references={activity.references} />
                        </span>
                    ) : null}
                </div>
                {activity.captureEvidence ? (
                    <ProviderCaptureEvidence evidence={activity.captureEvidence} compact />
                ) : null}
            </div>
        );
    } else if (entry.kind === 'comment') {
        const { comment } = entry;
        title = (
            <p className="line-clamp-2 text-sm text-foreground">
                <NoteContent content={comment.content ?? ''} references={comment.references} />
            </p>
        );
        subtitle = (
            <p className="mt-0.5 flex flex-wrap items-center gap-x-2 text-xs text-muted-foreground">
                <span>
                    {comment.author
                        ? t('commentBy', { name: comment.author.displayName })
                        : t('commentByRemovedMember')}
                </span>
                <span aria-hidden>·</span>
                <Link
                    href={commentThreadHref(pathname, searchParams, comment.id)}
                    className="text-foreground underline-offset-2 hover:underline"
                >
                    {t('commentViewInThread')}
                </Link>
            </p>
        );
    } else if (entry.kind === 'campaign') {
        const { campaign } = entry;
        const statusKey = CAMPAIGN_STATUS_KEY[campaign.status];
        const channelKey = CAMPAIGN_CHANNEL_KEY[campaign.channel];
        title = (
            <p className="text-sm text-foreground">
                <Link
                    href={`/marketing/campaigns/${campaign.campaignId}`}
                    className="underline-offset-2 hover:underline"
                >
                    {campaign.campaignName}
                </Link>
            </p>
        );
        subtitle = (
            <p className="mt-0.5 flex flex-wrap items-center gap-x-2 text-xs text-muted-foreground">
                <span>{channelKey ? t(channelKey) : campaign.channel}</span>
                {statusKey ? (
                    <>
                        <span aria-hidden>·</span>
                        <span>{t(statusKey)}</span>
                    </>
                ) : null}
            </p>
        );
    } else if (entry.kind === 'lifecycle') {
        const { lifecycle } = entry;
        title = (
            <p className="text-sm text-foreground">
                {tl('transition', {
                    from: lifecycleStageLabel(lifecycle.fromStage, tl),
                    to: lifecycleStageLabel(lifecycle.toStage, tl),
                })}
            </p>
        );
        const detail = [
            lifecycle.reason != null ? tl(`reason.${lifecycle.reason}`) : null,
            lifecycle.note ?? null,
        ].filter((part): part is string => part !== null).join(' · ');
        if (detail) {
            subtitle = <p className="mt-0.5 text-xs text-muted-foreground">{detail}</p>;
        }
    } else {
        title = (
            <p className="line-clamp-2 text-sm text-foreground">
                <NoteContent content={entry.note.content} references={entry.note.references} />
            </p>
        );
    }

    return (
        <li
            ref={rowRef}
            className={`flex scroll-mt-24 items-center gap-4 rounded-lg px-6 py-4 transition-colors duration-700 ${isHighlighted ? 'bg-brand-light/40' : ''}`}
        >
            <Tooltip>
                <TooltipTrigger asChild>
                    <Avatar size="default">
                        <AvatarImage src={avatarUrl} />
                        <AvatarFallback>
                            <UserIcon className="size-3 text-muted-foreground" />
                        </AvatarFallback>
                    </Avatar>
                </TooltipTrigger>
                <TooltipContent>
                    {avatarName}
                </TooltipContent>
            </Tooltip>
            <span className={`inline-flex items-center rounded-md px-2 py-1 text-xs font-medium inset-ring ${CHIP_CLASS[entry.kind]}`}>
                <span className="mr-1">●</span>{chipLabel}
            </span>
            <div className="min-w-0 flex-1">
                <div className="flex items-start justify-between gap-3">
                    {title}
                    {date ? (
                        <time className="shrink-0 text-xs text-muted-foreground">
                            {date}
                        </time>
                    ) : null}
                </div>
                {subtitle}
            </div>
            {!readOnlyEntry && (entry.kind !== 'activity' || !isProviderOwnedActivity(entry.activity)) ? (
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <IconButton variant="ghost" size="icon-inline" label={t('actionsAria')}>
                            <EllipsisVerticalIcon className="text-muted-foreground" />
                        </IconButton>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                        <DropdownMenuItem onClick={() => setEditOpen(true)}>
                            <PencilIcon className="size-4 text-muted-foreground" />
                            {t('edit')}
                        </DropdownMenuItem>
                        <DropdownMenuSeparator />
                        <DropdownMenuItem variant="destructive" onClick={handleDelete}>
                            <TrashIcon className="size-4" />
                            {t('delete')}
                        </DropdownMenuItem>
                    </DropdownMenuContent>
                </DropdownMenu>
            ) : null}

            {entry.kind === 'task' && (
                <EditTaskSheet
                    key={entry.task.id}
                    task={entry.task}
                    open={editOpen}
                    onOpenChange={setEditOpen}
                    companyId={companyId}
                    deals={deals}
                />
            )}
            {entry.kind === 'activity' && !isProviderOwnedActivity(entry.activity) && (
                <EditActivitySheet
                    key={entry.activity.id}
                    activity={entry.activity}
                    open={editOpen}
                    onOpenChange={setEditOpen}
                    persons={persons}
                    deals={deals}
                    originWorkspaceId={originWorkspaceId}
                />
            )}
            {entry.kind === 'note' && currentUserId != null && (
                <NoteDialog
                    key={entry.note.id}
                    note={entry.note}
                    open={editOpen}
                    onOpenChange={setEditOpen}
                    persons={personSearch.contacts}
                    deals={dealSearch.deals}
                    currentUserId={currentUserId}
                    onPersonQueryChange={personSearch.onInputValueChange}
                    onDealQueryChange={dealSearch.onInputValueChange}
                    personOptionsLoading={personSearch.loading}
                    dealOptionsLoading={dealSearch.loading}
                />
            )}
        </li>
    );
}
