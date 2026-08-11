'use client';

import { useState } from 'react';
import { useTranslations } from 'next-intl';

import type { RadarSignal } from '@/app/lib/types';
import { RADAR_FIELD_SURFACE } from '@/app/components/radar/radarControlSurface';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
    ResponsiveDialog,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogTitle,
} from '@/components/ui/responsive-dialog';

const MAX_SNOOZE_MS = 30 * 24 * 60 * 60 * 1_000;

function datetimeLocal(date: Date): string {
    const pad = (value: number) => String(value).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** Explicit snooze-time picker that never invents a duration on the user's behalf. */
export default function RadarSnoozeDialog({
    signal,
    open,
    busy,
    onOpenChange,
    onSnooze,
}: {
    signal: RadarSignal;
    open: boolean;
    busy: boolean;
    onOpenChange: (open: boolean) => void;
    onSnooze: (until: string) => void;
}) {
    const t = useTranslations('Radar');
    const [openedAt] = useState(Date.now);
    const [value, setValue] = useState('');
    const parsed = value.length > 0 ? new Date(value).getTime() : Number.NaN;
    const valid = Number.isFinite(parsed) && parsed > openedAt && parsed <= openedAt + MAX_SNOOZE_MS;

    return (
        <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
            <ResponsiveDialogContent className="sm:max-w-sm">
                <div className="space-y-1.5 px-6 pt-6 sm:px-0 sm:pt-0">
                    <ResponsiveDialogTitle>{t('snooze.title')}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>
                        {t('snooze.description', { subject: signal.subject.label })}
                    </ResponsiveDialogDescription>
                </div>
                <form
                    className="grid gap-5 px-6 pb-6 sm:px-0 sm:pb-0"
                    onSubmit={(event) => {
                        event.preventDefault();
                        if (valid && !busy) onSnooze(new Date(value).toISOString());
                    }}
                >
                    <div className="grid gap-2">
                        <Label htmlFor={`radar-snooze-${signal.id}`}>{t('snooze.untilLabel')}</Label>
                        <Input
                            id={`radar-snooze-${signal.id}`}
                            type="datetime-local"
                            value={value}
                            min={datetimeLocal(new Date(openedAt + 60_000))}
                            max={datetimeLocal(new Date(openedAt + MAX_SNOOZE_MS))}
                            onChange={(event) => setValue(event.target.value)}
                            aria-describedby={`radar-snooze-hint-${signal.id}`}
                            aria-invalid={value.length > 0 && !valid}
                            disabled={busy}
                            autoFocus
                            className={RADAR_FIELD_SURFACE}
                        />
                        <p
                            id={`radar-snooze-hint-${signal.id}`}
                            className={value.length > 0 && !valid ? 'text-xs text-destructive' : 'text-xs text-muted-foreground'}
                        >
                            {t('snooze.hint')}
                        </p>
                    </div>
                    <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
                        <Button type="button" variant="ghost" onClick={() => onOpenChange(false)} disabled={busy}>
                            {t('actions.cancel')}
                        </Button>
                        <Button type="submit" disabled={!valid || busy}>
                            {busy ? t('actions.snoozing') : t('actions.snooze')}
                        </Button>
                    </div>
                </form>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
