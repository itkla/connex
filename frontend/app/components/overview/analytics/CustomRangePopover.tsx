'use client';

import { useMemo, useState, type ReactNode } from 'react';
import { CalendarDaysIcon } from '@heroicons/react/16/solid';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
    Popover,
    PopoverContent,
    PopoverDescription,
    PopoverTitle,
    PopoverTrigger,
} from '@/components/ui/popover';
import {
    analyticsWindowDays,
    parseCustomAnalyticsWindow,
    type AnalyticsWindow,
} from '@/app/components/overview/analytics/metrics';

/** Localized copy required by the Analytics custom-range popover. */
export type CustomRangeLabels = {
    custom: string;
    title: string;
    description: string;
    start: string;
    end: string;
    hint: string;
    invalid: string;
    tooLong: string;
    cancel: string;
    apply: string;
};

function formatWindow(window: AnalyticsWindow, formatter: Intl.DateTimeFormat): string {
    return `${formatter.format(new Date(`${window.from}T00:00:00Z`))} – ${formatter.format(new Date(`${window.to}T00:00:00Z`))}`;
}

/** Anchored custom-range form used by the Analytics period segmented control. */
export default function CustomRangePopover({
    active,
    value,
    locale,
    labels,
    className,
    thumb,
    onApply,
}: {
    active: boolean;
    value: AnalyticsWindow;
    locale: string;
    labels: CustomRangeLabels;
    className: string;
    thumb: ReactNode;
    onApply: (window: AnalyticsWindow) => void;
}) {
    const [open, setOpen] = useState(false);
    const [from, setFrom] = useState(value.from);
    const [to, setTo] = useState(value.to);
    const formatters = useMemo(() => ({
        compact: new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric', timeZone: 'UTC' }),
        full: new Intl.DateTimeFormat(locale, {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
            timeZone: 'UTC',
        }),
    }), [locale]);
    const parsed = useMemo(() => parseCustomAnalyticsWindow(from, to), [from, to]);
    const complete = from.length > 0 && to.length > 0;
    const days = analyticsWindowDays({ from, to });
    const error = complete && !parsed
        ? days > 731
            ? labels.tooLong
            : labels.invalid
        : null;

    const setOpenWithDraft = (next: boolean) => {
        if (next) {
            setFrom(value.from);
            setTo(value.to);
        }
        setOpen(next);
    };

    return (
        <Popover open={open} onOpenChange={setOpenWithDraft}>
            <PopoverTrigger
                type="button"
                aria-pressed={active}
                aria-label={active ? `${labels.custom}: ${formatWindow(value, formatters.full)}` : labels.custom}
                className={className}
            >
                {active ? thumb : null}
                <span className="relative z-10 inline-flex items-center gap-1.5">
                    <CalendarDaysIcon className="size-3.5 text-muted-foreground" />
                    {active ? formatWindow(value, formatters.compact) : labels.custom}
                </span>
            </PopoverTrigger>
            <PopoverContent align="end">
                <PopoverTitle className="text-sm font-semibold text-foreground">{labels.title}</PopoverTitle>
                <PopoverDescription className="mt-1 text-xs leading-relaxed text-muted-foreground">
                    {labels.description}
                </PopoverDescription>
                <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2">
                    <div className="grid gap-1.5">
                        <Label htmlFor="analytics-custom-from">{labels.start}</Label>
                        <Input
                            id="analytics-custom-from"
                            type="date"
                            value={from}
                            max={to || undefined}
                            onChange={(event) => setFrom(event.target.value)}
                            aria-invalid={error != null}
                            aria-describedby="analytics-custom-range-status"
                        />
                    </div>
                    <div className="grid gap-1.5">
                        <Label htmlFor="analytics-custom-to">{labels.end}</Label>
                        <Input
                            id="analytics-custom-to"
                            type="date"
                            value={to}
                            min={from || undefined}
                            onChange={(event) => setTo(event.target.value)}
                            aria-invalid={error != null}
                            aria-describedby="analytics-custom-range-status"
                        />
                    </div>
                </div>
                <p
                    id="analytics-custom-range-status"
                    className={error ? 'mt-2 text-xs text-destructive' : 'mt-2 text-xs text-muted-foreground'}
                    role={error ? 'alert' : undefined}
                >
                    {error ?? labels.hint}
                </p>
                <div className="mt-4 flex justify-end gap-2">
                    <Button variant="ghost" size="sm" onClick={() => setOpen(false)}>
                        {labels.cancel}
                    </Button>
                    <Button
                        variant="brand"
                        size="sm"
                        disabled={!parsed}
                        onClick={() => {
                            if (!parsed) return;
                            onApply(parsed);
                            setOpen(false);
                        }}
                    >
                        {labels.apply}
                    </Button>
                </div>
            </PopoverContent>
        </Popover>
    );
}
