'use client';

import { useMemo, useState, type ReactNode } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import {
    CalendarDaysIcon,
    ExclamationTriangleIcon,
} from '@heroicons/react/24/outline';

import { parseMysqlDateTime } from '@/app/lib/utils';
import type {
    ReportSchedule,
    ReportScheduleCadence,
    ReportScheduleRequest,
    WorkspaceMember,
} from '@/app/lib/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
    Combobox,
    ComboboxChip,
    ComboboxChips,
    ComboboxChipsInput,
    ComboboxContent,
    ComboboxEmpty,
    ComboboxInput,
    ComboboxItem,
    ComboboxList,
    ComboboxValue,
    useComboboxAnchor,
} from '@/components/ui/combobox';
import { Label } from '@/components/ui/label';
import {
    ResponsiveDialog,
    ResponsiveDialogClose,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
} from '@/components/ui/responsive-dialog';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { Switch } from '@/components/ui/switch';

type ScheduleDialogProps = {
    open: boolean;
    schedule: ReportSchedule | null;
    members: WorkspaceMember[];
    canManage: boolean;
    loading: boolean;
    loadFailed: boolean;
    membersFailed: boolean;
    defaultTimezone: string;
    onOpenChange: (open: boolean) => void;
    onRetry: () => void;
    onSubmit: (payload: ReportScheduleRequest) => Promise<void>;
    onRequestDelete: () => void;
};

/** Permission-aware editor and read-only view for a report's single delivery schedule. */
export default function ScheduleDialog({
    open,
    schedule,
    members,
    canManage,
    loading,
    loadFailed,
    membersFailed,
    defaultTimezone,
    onOpenChange,
    onRetry,
    onSubmit,
    onRequestDelete,
}: ScheduleDialogProps) {
    const [saving, setSaving] = useState(false);

    const handleOpenChange = (next: boolean) => {
        if (!next && saving) return;
        onOpenChange(next);
    };

    const handleSubmit = async (payload: ReportScheduleRequest) => {
        setSaving(true);
        try {
            await onSubmit(payload);
            onOpenChange(false);
        } finally {
            setSaving(false);
        }
    };

    const managementAvailable = canManage && !membersFailed;

    return (
        <ResponsiveDialog open={open} onOpenChange={handleOpenChange}>
            <ResponsiveDialogContent className="sm:max-w-2xl" showCloseButton={false}>
                {open ? (
                    loading ? (
                        <ScheduleLoading />
                    ) : loadFailed ? (
                        <ScheduleLoadError onRetry={onRetry} />
                    ) : managementAvailable ? (
                        <ScheduleForm
                            key={schedule ? `schedule-${schedule.id}-${schedule.updatedAt}` : 'new-schedule'}
                            schedule={schedule}
                            members={members}
                            defaultTimezone={defaultTimezone}
                            saving={saving}
                            onSubmit={handleSubmit}
                            onRequestDelete={onRequestDelete}
                        />
                    ) : (
                        <ScheduleReadOnly
                            schedule={schedule}
                            canManage={canManage}
                            membersFailed={membersFailed}
                            onRetry={onRetry}
                        />
                    )
                ) : null}
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}

function ScheduleForm({
    schedule,
    members,
    defaultTimezone,
    saving,
    onSubmit,
    onRequestDelete,
}: {
    schedule: ReportSchedule | null;
    members: WorkspaceMember[];
    defaultTimezone: string;
    saving: boolean;
    onSubmit: (payload: ReportScheduleRequest) => Promise<void>;
    onRequestDelete: () => void;
}) {
    const t = useTranslations('Reports');
    const locale = useLocale();
    const recipientAnchor = useComboboxAnchor();
    const [cadence, setCadence] = useState<ReportScheduleCadence>(schedule?.cadence ?? 'monthly');
    const [selectedMembers, setSelectedMembers] = useState(() =>
        members.filter((member) => schedule?.recipientUserIds.includes(member.id)));
    const [timezone, setTimezone] = useState((schedule?.timezone ?? defaultTimezone) || 'UTC');
    const [hourOfDay, setHourOfDay] = useState(schedule?.hourOfDay ?? 9);
    const [enabled, setEnabled] = useState(schedule?.enabled ?? true);
    const [error, setError] = useState<{ fieldId: string; message: string } | null>(null);
    const timezones = useMemo(
        () => supportedTimezones(timezone, defaultTimezone),
        [timezone, defaultTimezone],
    );
    const recipientsChanged = useMemo(() => {
        if (schedule === null) {
            return false;
        }
        const activeIds = new Set(members.map((member) => member.id));
        return schedule.recipientUserIds.some((id) => !activeIds.has(id));
    }, [schedule, members]);

    const fail = (fieldId: string, message: string) => {
        setError({ fieldId, message });
        requestAnimationFrame(() => document.getElementById(fieldId)?.focus());
    };

    const submit = () => {
        setError(null);
        if (selectedMembers.length === 0) {
            fail('schedule-recipients', t('schedule.validation.recipients'));
            return;
        }
        if (selectedMembers.length > 100) {
            fail('schedule-recipients', t('schedule.validation.recipientLimit'));
            return;
        }
        if (!timezone || !timezones.includes(timezone)) {
            fail('schedule-timezone', t('schedule.validation.timezone'));
            return;
        }
        if (!Number.isInteger(hourOfDay) || hourOfDay < 0 || hourOfDay > 23) {
            fail('schedule-hour', t('schedule.validation.hour'));
            return;
        }
        void onSubmit({
            cadence,
            recipientUserIds: selectedMembers.map((member) => member.id),
            timezone,
            hourOfDay,
            enabled,
        }).catch(() => undefined);
    };

    return (
        <form
            onSubmit={(event) => {
                event.preventDefault();
                submit();
            }}
            className="grid gap-5"
        >
            <ScheduleHeader />

            <div className="grid gap-4 sm:grid-cols-2">
                <div className="grid gap-1.5">
                    <Label htmlFor="schedule-cadence">{t('schedule.cadence')}</Label>
                    <Select
                        value={cadence}
                        onValueChange={(value) => {
                            if (isScheduleCadence(value)) setCadence(value);
                        }}
                        disabled={saving}
                    >
                        <SelectTrigger id="schedule-cadence" className="w-full">
                            <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                            <SelectItem value="weekly">{t('cadence.weekly')}</SelectItem>
                            <SelectItem value="monthly">{t('cadence.monthly')}</SelectItem>
                            <SelectItem value="quarterly">{t('cadence.quarterly')}</SelectItem>
                        </SelectContent>
                    </Select>
                </div>

                <div className="grid gap-1.5">
                    <Label htmlFor="schedule-hour">{t('schedule.hour')}</Label>
                    <Select
                        value={String(hourOfDay)}
                        onValueChange={(value) => {
                            const nextHour = Number(value);
                            if (Number.isInteger(nextHour) && nextHour >= 0 && nextHour <= 23) {
                                setHourOfDay(nextHour);
                            }
                        }}
                        disabled={saving}
                    >
                        <SelectTrigger
                            id="schedule-hour"
                            className="w-full"
                            aria-invalid={error?.fieldId === 'schedule-hour'}
                            aria-describedby={error?.fieldId === 'schedule-hour' ? 'schedule-form-error' : undefined}
                        >
                            <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                            {Array.from({ length: 24 }, (_, hour) => (
                                <SelectItem key={hour} value={String(hour)}>
                                    {formatHour(hour, locale)}
                                </SelectItem>
                            ))}
                        </SelectContent>
                    </Select>
                </div>

                <div className="grid gap-1.5 sm:col-span-2">
                    <Label htmlFor="schedule-recipients">{t('schedule.recipients')}</Label>
                    <Combobox
                        items={members}
                        value={selectedMembers}
                        onValueChange={setSelectedMembers}
                        itemToStringLabel={(member) => member.displayName}
                        isItemEqualToValue={(member, selected) => member.id === selected.id}
                        multiple
                        disabled={saving}
                    >
                        <ComboboxChips ref={recipientAnchor}>
                            <ComboboxValue>
                                {(selected: WorkspaceMember[]) => (
                                    <>
                                        {selected.map((member) => (
                                            <ComboboxChip
                                                key={member.id}
                                                removeLabel={t('schedule.removeRecipient', { name: member.displayName })}
                                            >
                                                {member.displayName}
                                            </ComboboxChip>
                                        ))}
                                        <ComboboxChipsInput
                                            id="schedule-recipients"
                                            placeholder={selected.length === 0 ? t('schedule.recipientsPlaceholder') : undefined}
                                            disabled={saving}
                                            aria-invalid={error?.fieldId === 'schedule-recipients'}
                                            aria-describedby={error?.fieldId === 'schedule-recipients'
                                                ? 'schedule-form-error'
                                                : recipientsChanged
                                                    ? 'schedule-recipients-hint schedule-recipients-changed'
                                                    : 'schedule-recipients-hint'}
                                        />
                                    </>
                                )}
                            </ComboboxValue>
                        </ComboboxChips>
                        <ComboboxContent anchor={recipientAnchor} className="pointer-events-auto">
                            <ComboboxList>
                                <ComboboxEmpty>{t('schedule.recipientsEmpty')}</ComboboxEmpty>
                                {members.map((member) => (
                                    <ComboboxItem key={member.id} value={member}>
                                        <span className="min-w-0">
                                            <span className="block truncate font-medium text-foreground">
                                                {member.displayName}
                                            </span>
                                            <span className="block truncate text-xs text-muted-foreground">
                                                {member.email}
                                            </span>
                                        </span>
                                    </ComboboxItem>
                                ))}
                            </ComboboxList>
                        </ComboboxContent>
                    </Combobox>
                    <p id="schedule-recipients-hint" className="text-xs text-muted-foreground">
                        {t('schedule.recipientsHint')}
                    </p>
                    {recipientsChanged ? (
                        <p id="schedule-recipients-changed" className="text-xs text-destructive">
                            {t('schedule.recipientsChanged')}
                        </p>
                    ) : null}
                </div>

                <div className="grid gap-1.5 sm:col-span-2">
                    <Label htmlFor="schedule-timezone">{t('schedule.timezone')}</Label>
                    <Combobox
                        items={timezones}
                        value={timezone}
                        onValueChange={(value) => {
                            if (typeof value === 'string') setTimezone(value);
                        }}
                        itemToStringLabel={(value) => value}
                        disabled={saving}
                    >
                        <ComboboxInput
                            id="schedule-timezone"
                            placeholder={t('schedule.timezonePlaceholder')}
                            disabled={saving}
                            showTrigger={false}
                            aria-invalid={error?.fieldId === 'schedule-timezone'}
                            aria-describedby={error?.fieldId === 'schedule-timezone' ? 'schedule-form-error' : undefined}
                        />
                        <ComboboxContent className="pointer-events-auto">
                            <ComboboxList>
                                <ComboboxEmpty>{t('schedule.timezoneEmpty')}</ComboboxEmpty>
                                {timezones.map((value) => (
                                    <ComboboxItem key={value} value={value}>{value}</ComboboxItem>
                                ))}
                            </ComboboxList>
                        </ComboboxContent>
                    </Combobox>
                </div>

                <div className="flex items-center justify-between gap-4 rounded-xl border border-border bg-muted/30 px-4 py-3 sm:col-span-2">
                    <div>
                        <Label htmlFor="schedule-enabled">{t('schedule.enabled')}</Label>
                        <p className="mt-1 text-xs text-muted-foreground">{t('schedule.enabledHint')}</p>
                    </div>
                    <Switch
                        id="schedule-enabled"
                        checked={enabled}
                        onCheckedChange={setEnabled}
                        disabled={saving}
                    />
                </div>
            </div>

            {schedule ? <ScheduleMetadata schedule={schedule} /> : null}

            {error ? (
                <p id="schedule-form-error" role="alert" aria-live="assertive" className="text-sm text-destructive">
                    {error.message}
                </p>
            ) : null}

            <ResponsiveDialogFooter className="sm:justify-between">
                {schedule ? (
                    <Button type="button" variant="destructive" onClick={onRequestDelete} disabled={saving}>
                        {t('schedule.delete')}
                    </Button>
                ) : <span />}
                <div className="flex flex-col-reverse gap-2 sm:flex-row">
                    <ResponsiveDialogClose asChild>
                        <Button type="button" variant="outline" disabled={saving}>{t('common.cancel')}</Button>
                    </ResponsiveDialogClose>
                    <Button type="submit" variant="brand" disabled={saving}>
                        {saving
                            ? t('common.saving')
                            : schedule ? t('schedule.saveChanges') : t('schedule.save')}
                    </Button>
                </div>
            </ResponsiveDialogFooter>
        </form>
    );
}

function ScheduleReadOnly({
    schedule,
    canManage,
    membersFailed,
    onRetry,
}: {
    schedule: ReportSchedule | null;
    canManage: boolean;
    membersFailed: boolean;
    onRetry: () => void;
}) {
    const t = useTranslations('Reports');
    const locale = useLocale();
    return (
        <div className="grid gap-5">
            <ScheduleHeader />

            {!canManage ? (
                <div className="rounded-xl border border-border bg-muted/40 px-4 py-3 text-sm text-muted-foreground">
                    {t('schedule.readOnly')}
                </div>
            ) : null}

            {membersFailed ? (
                <div role="alert" className="rounded-xl border border-destructive/30 bg-destructive/5 px-4 py-3">
                    <p className="text-sm text-destructive">{t('schedule.membersLoadError')}</p>
                    <Button type="button" variant="outline" size="sm" className="mt-3" onClick={onRetry}>
                        {t('common.retry')}
                    </Button>
                </div>
            ) : null}

            {schedule ? (
                <>
                    <dl className="grid gap-4 rounded-xl border border-border bg-card p-4 sm:grid-cols-2">
                        <ReadOnlyField label={t('schedule.status')}>
                            <Badge variant={schedule.enabled ? 'default' : 'secondary'}>
                                {schedule.enabled ? t('schedule.active') : t('schedule.paused')}
                            </Badge>
                        </ReadOnlyField>
                        <ReadOnlyField label={t('schedule.cadence')}>
                            {t(`cadence.${schedule.cadence}`)}
                        </ReadOnlyField>
                        <ReadOnlyField label={t('schedule.hour')}>
                            {formatHour(schedule.hourOfDay, locale)}
                        </ReadOnlyField>
                        <ReadOnlyField label={t('schedule.timezone')}>
                            {schedule.timezone}
                        </ReadOnlyField>
                        <ReadOnlyField label={t('schedule.recipients')} className="sm:col-span-2">
                            {schedule.recipients.length > 0 ? (
                                <>
                                    <ul className="grid gap-2 sm:grid-cols-2">
                                        {schedule.recipients.map((recipient) => (
                                            <li key={recipient.userId} className="min-w-0 rounded-lg bg-muted/50 px-3 py-2">
                                                <span className="block truncate font-medium text-foreground">
                                                    {recipient.displayName}
                                                </span>
                                                <span className="block truncate text-xs text-muted-foreground">
                                                    {recipient.email}
                                                </span>
                                            </li>
                                        ))}
                                    </ul>
                                    {schedule.recipients.length < schedule.recipientUserIds.length ? (
                                        <p className="mt-2 text-xs text-destructive">
                                            {t('schedule.recipientsChangedReadOnly')}
                                        </p>
                                    ) : null}
                                </>
                            ) : t('schedule.recipientsUnavailable')}
                        </ReadOnlyField>
                    </dl>
                    <ScheduleMetadata schedule={schedule} />
                </>
            ) : (
                <div className="rounded-xl border border-dashed border-border bg-card/40 px-6 py-10 text-center">
                    <CalendarDaysIcon className="mx-auto size-7 text-muted-foreground" />
                    <h3 className="mt-4 text-base font-semibold text-foreground">{t('schedule.emptyTitle')}</h3>
                    <p className="mx-auto mt-1 max-w-md text-sm text-muted-foreground">{t('schedule.emptyBody')}</p>
                </div>
            )}

            <ResponsiveDialogFooter>
                <ResponsiveDialogClose asChild>
                    <Button type="button" variant="outline">{t('schedule.close')}</Button>
                </ResponsiveDialogClose>
            </ResponsiveDialogFooter>
        </div>
    );
}

function ScheduleHeader() {
    const t = useTranslations('Reports');
    return (
        <ResponsiveDialogHeader>
            <ResponsiveDialogTitle>{t('schedule.title')}</ResponsiveDialogTitle>
            <ResponsiveDialogDescription>{t('schedule.description')}</ResponsiveDialogDescription>
        </ResponsiveDialogHeader>
    );
}

function ScheduleMetadata({ schedule }: { schedule: ReportSchedule }) {
    const t = useTranslations('Reports');
    const locale = useLocale();
    return (
        <dl className="grid gap-3 rounded-xl bg-muted/40 px-4 py-3 sm:grid-cols-3">
            <ReadOnlyField label={t('schedule.nextRun')}>
                {formatScheduleTimestamp(schedule.nextRunAt, locale, schedule.timezone)}
            </ReadOnlyField>
            <ReadOnlyField label={t('schedule.lastRun')}>
                {schedule.lastRunAt
                    ? formatScheduleTimestamp(schedule.lastRunAt, locale, schedule.timezone)
                    : t('schedule.notYetRun')}
            </ReadOnlyField>
            <ReadOnlyField label={t('schedule.runAs')}>
                {schedule.runAsLabel ?? t('schedule.unavailableMember')}
            </ReadOnlyField>
        </dl>
    );
}

function ReadOnlyField({
    label,
    className,
    children,
}: {
    label: string;
    className?: string;
    children: ReactNode;
}) {
    return (
        <div className={className}>
            <dt className="text-xs font-medium text-muted-foreground">{label}</dt>
            <dd className="mt-1 text-sm text-foreground">{children}</dd>
        </div>
    );
}

function ScheduleLoading() {
    const t = useTranslations('Reports');
    return (
        <div className="grid gap-5" aria-busy="true">
            <ScheduleHeader />
            <p className="text-sm text-muted-foreground">{t('schedule.loading')}</p>
            <div className="grid gap-4 sm:grid-cols-2">
                <Skeleton className="h-9" />
                <Skeleton className="h-9" />
                <Skeleton className="h-20 sm:col-span-2" />
                <Skeleton className="h-9 sm:col-span-2" />
            </div>
            <ResponsiveDialogFooter>
                <ResponsiveDialogClose asChild>
                    <Button type="button" variant="outline">{t('schedule.close')}</Button>
                </ResponsiveDialogClose>
            </ResponsiveDialogFooter>
        </div>
    );
}

function ScheduleLoadError({ onRetry }: { onRetry: () => void }) {
    const t = useTranslations('Reports');
    return (
        <div className="grid gap-5">
            <ScheduleHeader />
            <div role="alert" className="rounded-xl border border-destructive/30 bg-destructive/5 px-5 py-8 text-center">
                <ExclamationTriangleIcon className="mx-auto size-7 text-destructive" />
                <h3 className="mt-4 text-base font-semibold text-foreground">{t('schedule.loadErrorTitle')}</h3>
                <p className="mx-auto mt-1 max-w-md text-sm text-muted-foreground">{t('schedule.loadErrorBody')}</p>
                <Button type="button" variant="outline" className="mt-5" onClick={onRetry}>
                    {t('common.retry')}
                </Button>
            </div>
            <ResponsiveDialogFooter>
                <ResponsiveDialogClose asChild>
                    <Button type="button" variant="outline">{t('schedule.close')}</Button>
                </ResponsiveDialogClose>
            </ResponsiveDialogFooter>
        </div>
    );
}

function supportedTimezones(current: string, fallback: string): string[] {
    let supported: string[] = [];
    try {
        supported = Intl.supportedValuesOf('timeZone');
    } catch {
        supported = [];
    }
    return Array.from(new Set([current, fallback, 'UTC', ...supported].filter(Boolean)));
}

function isScheduleCadence(value: string): value is ReportScheduleCadence {
    return value === 'weekly' || value === 'monthly' || value === 'quarterly';
}

function formatHour(hour: number, locale: string): string {
    return new Intl.DateTimeFormat(locale, {
        hour: 'numeric',
        minute: '2-digit',
        hourCycle: 'h23',
        timeZone: 'UTC',
    }).format(new Date(Date.UTC(2020, 0, 1, hour)));
}

function formatScheduleTimestamp(value: string, locale: string, timezone: string): string {
    const timestamp = parseMysqlDateTime(value);
    if (Number.isNaN(timestamp)) return value;
    return new Intl.DateTimeFormat(locale, {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: 'numeric',
        minute: '2-digit',
        timeZone: timezone,
        timeZoneName: 'short',
    }).format(new Date(timestamp));
}
