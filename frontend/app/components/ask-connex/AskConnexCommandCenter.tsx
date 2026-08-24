'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import {
    BellAlertIcon,
    EllipsisHorizontalIcon,
    PauseCircleIcon,
    PlayCircleIcon,
    TrashIcon,
} from '@heroicons/react/24/outline';

import { IconButton } from '@/components/ui/icon-button';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import {
    ResponsiveDialog,
    ResponsiveDialogClose,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
} from '@/components/ui/responsive-dialog';
import { Button } from '@/components/ui/button';
import { Separator } from '@/components/ui/separator';
import { Skeleton } from '@/components/ui/skeleton';
import { Switch } from '@/components/ui/switch';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import {
    deleteAiWatch,
    getAiCommandCenter,
    replaceAiBriefSchedule,
    setAiWatchActive,
} from '@/app/lib/api';
import type {
    AiBriefSchedulePayload,
    AiCommandCenter,
    AiWatch,
} from '@/app/lib/types';
import { formatDate, formatUtcDateTime } from '@/app/lib/utils';
import {
    askConnexWatchHref,
    askConnexWatchLimitsText,
    askConnexWatchTriggerText,
} from '@/app/components/ask-connex/askConnexWatch';

/** Local hours a brief may be scheduled at. */
const HOURS = Array.from({ length: 24 }, (_, hour) => hour);

/** ISO weekdays, Monday first, matching the server's 1-7 encoding. */
const WEEKDAYS = [1, 2, 3, 4, 5, 6, 7];

/**
 * The standing work Ask Connex is doing when nobody is asking it anything.
 *
 * <p>It is a view, not an owner. The brief schedule and the watch list are the only state it
 * writes; the brief itself is a session, the evidence behind a fired watch is the record's, and
 * read/dismiss belongs to notifications. That is why nothing here is a card of results: it is two
 * short settings sections that say what is switched on and let the member switch it off.
 *
 * It renders only on the routed workspace mount's no-session state, where the member has arrived
 * without a question in mind. In the drawer, which is opened mid-task from a record, it would be an
 * interruption.
 *
 * Each watch row states its whole contract — the trigger, how often it may repeat, and when it
 * stops — because those terms bind whether or not they were chosen, and a member cannot decide to
 * keep a watch they can only half read. Deleting one is confirmed like any other destructive act.
 */
export default function AskConnexCommandCenter() {
    const t = useTranslations('AskConnex.commandCenter');
    const locale = useLocale();
    const showApiError = useApiErrorToast('AskConnex.commandCenter');
    const [state, setState] = useState<AiCommandCenter | null>(null);
    const [loadState, setLoadState] = useState<'loading' | 'ready' | 'unavailable'>('loading');
    const [savingSchedule, setSavingSchedule] = useState(false);
    const [busyWatchId, setBusyWatchId] = useState<number | null>(null);
    const [pendingDeletion, setPendingDeletion] = useState<AiWatch | null>(null);

    useEffect(() => {
        let live = true;
        getAiCommandCenter()
            .then((loaded) => {
                if (!live) return;
                setState(loaded);
                setLoadState('ready');
            })
            .catch(() => {
                // The command centre is standing context, not the reason the member opened Ask
                // Connex. A failed read hides the section and leaves the composer entirely usable
                // rather than putting an error where a prompt should be.
                if (live) setLoadState('unavailable');
            });
        return () => {
            live = false;
        };
    }, []);

    const saveSchedule = useCallback(
        async (next: AiBriefSchedulePayload) => {
            const previous = state;
            if (previous === null) return;
            setState({ ...previous, schedule: { ...previous.schedule, ...next } });
            setSavingSchedule(true);
            try {
                const saved = await replaceAiBriefSchedule(next);
                setState((current) => (current === null ? current : { ...current, schedule: saved }));
            } catch (error) {
                setState(previous);
                showApiError(error, 'scheduleFailed');
            } finally {
                setSavingSchedule(false);
            }
        },
        [showApiError, state],
    );

    const toggleWatch = useCallback(
        async (watch: AiWatch) => {
            setBusyWatchId(watch.id);
            try {
                const saved = await setAiWatchActive(watch.id, watch.status !== 'active');
                setState((current) => (current === null ? current : {
                    ...current,
                    watches: current.watches.map((row) => (row.id === saved.id ? saved : row)),
                }));
            } catch (error) {
                showApiError(error, 'watchFailed');
            } finally {
                setBusyWatchId(null);
            }
        },
        [showApiError],
    );

    const removeWatch = useCallback(
        async (watch: AiWatch) => {
            setBusyWatchId(watch.id);
            try {
                await deleteAiWatch(watch.id);
                setState((current) => (current === null ? current : {
                    ...current,
                    watches: current.watches.filter((row) => row.id !== watch.id),
                }));
                setPendingDeletion(null);
            } catch (error) {
                showApiError(error, 'watchFailed');
            } finally {
                setBusyWatchId(null);
            }
        },
        [showApiError],
    );

    if (loadState === 'unavailable') return null;

    if (loadState === 'loading' || state === null) {
        return (
            <section aria-busy className="w-full max-w-xl space-y-3 text-left">
                <Skeleton className="h-4 w-28" />
                <Skeleton className="h-9 w-full" />
                <Skeleton className="h-9 w-full" />
            </section>
        );
    }

    const { schedule, watches } = state;
    const scheduleBase: AiBriefSchedulePayload = {
        timeZone: schedule.timeZone,
        dailyEnabled: schedule.dailyEnabled,
        dailyHour: schedule.dailyHour,
        weeklyEnabled: schedule.weeklyEnabled,
        weeklyWeekday: schedule.weeklyWeekday,
        weeklyHour: schedule.weeklyHour,
    };

    return (
        <section className="w-full max-w-xl space-y-6 text-left">
            <div className="space-y-3">
                <div className="flex items-baseline justify-between gap-3">
                    <h3 className="text-sm font-medium text-foreground">{t('briefsTitle')}</h3>
                    {state.latestBriefSessionId !== null ? (
                        <Link
                            href={`/ask-connex/${state.latestBriefSessionId}`}
                            className="text-xs text-primary underline-offset-4 hover:underline"
                        >
                            {t('openLatestBrief')}
                        </Link>
                    ) : null}
                </div>
                <p className="text-xs leading-relaxed text-muted-foreground">
                    {state.briefSkillAvailable ? t('briefsBody') : t('briefsUnavailable')}
                </p>
                <div className="space-y-2">
                    <div className="flex flex-wrap items-center gap-3">
                        <Switch
                            id="ask-connex-daily-brief"
                            checked={schedule.dailyEnabled}
                            disabled={savingSchedule || !state.briefSkillAvailable}
                            onCheckedChange={(checked) => void saveSchedule({
                                ...scheduleBase,
                                dailyEnabled: checked === true,
                            })}
                        />
                        <label
                            htmlFor="ask-connex-daily-brief"
                            className="text-sm text-foreground"
                        >
                            {t('dailyLabel')}
                        </label>
                        {schedule.dailyEnabled ? (
                            <Select
                                value={String(schedule.dailyHour)}
                                disabled={savingSchedule}
                                onValueChange={(value) => void saveSchedule({
                                    ...scheduleBase,
                                    dailyHour: Number(value),
                                })}
                            >
                                <SelectTrigger
                                    size="sm"
                                    className="w-28"
                                    aria-label={t('dailyHourLabel')}
                                >
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    {HOURS.map((hour) => (
                                        <SelectItem key={`hour-${hour}`} value={String(hour)}>
                                            {t('hour', { hour })}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        ) : null}
                    </div>
                    <div className="flex flex-wrap items-center gap-3">
                        <Switch
                            id="ask-connex-weekly-brief"
                            checked={schedule.weeklyEnabled}
                            disabled={savingSchedule || !state.briefSkillAvailable}
                            onCheckedChange={(checked) => void saveSchedule({
                                ...scheduleBase,
                                weeklyEnabled: checked === true,
                            })}
                        />
                        <label
                            htmlFor="ask-connex-weekly-brief"
                            className="text-sm text-foreground"
                        >
                            {t('weeklyLabel')}
                        </label>
                        {schedule.weeklyEnabled ? (
                            <Select
                                value={String(schedule.weeklyWeekday)}
                                disabled={savingSchedule}
                                onValueChange={(value) => void saveSchedule({
                                    ...scheduleBase,
                                    weeklyWeekday: Number(value),
                                })}
                            >
                                <SelectTrigger
                                    size="sm"
                                    className="w-32"
                                    aria-label={t('weeklyDayLabel')}
                                >
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    {WEEKDAYS.map((weekday) => (
                                        <SelectItem key={`weekday-${weekday}`} value={String(weekday)}>
                                            {t(`weekday.${weekday}`)}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        ) : null}
                    </div>
                </div>
                <p className="text-xs text-muted-foreground">
                    {t('scheduleZone', { zone: schedule.timeZone })}
                </p>
            </div>

            <Separator />

            <div className="space-y-3">
                <h3 className="text-sm font-medium text-foreground">{t('watchesTitle')}</h3>
                {watches.length === 0 ? (
                    <p className="text-xs leading-relaxed text-muted-foreground">
                        {t('watchesEmpty')}
                    </p>
                ) : (
                    <ul className="space-y-1">
                        {watches.map((watch) => {
                            const limits = askConnexWatchLimitsText(
                                watch, t, (isoDate) => formatDate(isoDate, locale),
                            );
                            return (
                            <li
                                key={`watch-${watch.id}`}
                                className="flex items-start justify-between gap-3 rounded-lg px-2 py-2 hover:bg-muted/60"
                            >
                                <div className="min-w-0 space-y-0.5">
                                    <p className="text-sm text-foreground">
                                        {askConnexWatchTriggerText(watch, t)}
                                    </p>
                                    <p className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-muted-foreground">
                                        {watch.subjectLabel === null ? (
                                            <span>{t('subjectUnavailable')}</span>
                                        ) : (
                                            <Link
                                                href={askConnexWatchHref(watch)}
                                                className="underline-offset-4 hover:underline"
                                            >
                                                {watch.subjectLabel}
                                            </Link>
                                        )}
                                        <span aria-hidden>·</span>
                                        <span>
                                            {watch.status === 'active'
                                                ? t('statusActive')
                                                : t('statusPaused')}
                                        </span>
                                        <span aria-hidden>·</span>
                                        <span>
                                            {watch.lastFiredAt === null
                                                ? t('neverFired')
                                                : t('lastFired', {
                                                    date: formatUtcDateTime(
                                                        watch.lastFiredAt, locale,
                                                    ),
                                                })}
                                        </span>
                                        <span aria-hidden>·</span>
                                        <span>{limits.cooldown}</span>
                                        <span aria-hidden>·</span>
                                        <span>{limits.expiry}</span>
                                    </p>
                                </div>
                                <DropdownMenu>
                                    <DropdownMenuTrigger asChild>
                                        <IconButton
                                            variant="ghost"
                                            size="icon-inline"
                                            label={t('watchActions')}
                                            disabled={busyWatchId === watch.id}
                                        >
                                            <EllipsisHorizontalIcon aria-hidden />
                                        </IconButton>
                                    </DropdownMenuTrigger>
                                    <DropdownMenuContent align="end">
                                        <DropdownMenuItem onSelect={() => void toggleWatch(watch)}>
                                            {watch.status === 'active' ? (
                                                <PauseCircleIcon aria-hidden />
                                            ) : (
                                                <PlayCircleIcon aria-hidden />
                                            )}
                                            {watch.status === 'active' ? t('pause') : t('resume')}
                                        </DropdownMenuItem>
                                        <DropdownMenuItem
                                            variant="destructive"
                                            onSelect={() => setPendingDeletion(watch)}
                                        >
                                            <TrashIcon aria-hidden />
                                            {t('delete')}
                                        </DropdownMenuItem>
                                    </DropdownMenuContent>
                                </DropdownMenu>
                            </li>
                            );
                        })}
                    </ul>
                )}
                <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
                    <BellAlertIcon aria-hidden className="size-3.5 shrink-0" />
                    {t('watchesHint')}
                </p>
                <p className="text-xs text-muted-foreground">
                    {t('watchesUsage', { used: watches.length, limit: state.watchLimit })}
                </p>
            </div>

            <ResponsiveDialog
                open={pendingDeletion !== null}
                onOpenChange={(open) => {
                    if (!open) setPendingDeletion(null);
                }}
            >
                <ResponsiveDialogContent className="sm:max-w-md">
                    <ResponsiveDialogHeader>
                        <ResponsiveDialogTitle>{t('deleteTitle')}</ResponsiveDialogTitle>
                        <ResponsiveDialogDescription>
                            {pendingDeletion === null
                                ? t('deleteBody')
                                : t('deleteBodyNamed', {
                                    trigger: askConnexWatchTriggerText(pendingDeletion, t),
                                })}
                        </ResponsiveDialogDescription>
                    </ResponsiveDialogHeader>
                    <ResponsiveDialogFooter>
                        <ResponsiveDialogClose asChild>
                            <Button variant="outline">{t('createCancel')}</Button>
                        </ResponsiveDialogClose>
                        <Button
                            type="button"
                            variant="destructive"
                            disabled={pendingDeletion !== null
                                && busyWatchId === pendingDeletion.id}
                            onClick={() => {
                                if (pendingDeletion !== null) void removeWatch(pendingDeletion);
                            }}
                        >
                            {t('delete')}
                        </Button>
                    </ResponsiveDialogFooter>
                </ResponsiveDialogContent>
            </ResponsiveDialog>
        </section>
    );
}
