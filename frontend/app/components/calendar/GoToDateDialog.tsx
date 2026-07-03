'use client';

import { useRef } from 'react';
import { useTranslations } from 'next-intl';

import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { dayKeyOf } from '@/app/lib/calendar';
import { parseCalendarDate } from '@/app/lib/utils';

/**
 * Compact date-jump dialog. The month/day input is uncontrolled and re-seeds to the current
 * anchor each time the dialog mounts (Radix unmounts content on close), so no reset effect is
 * needed. Submitting or picking Today jumps the calendar and closes.
 */
export default function GoToDateDialog({
    open,
    onOpenChange,
    initialDate,
    onPick,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    initialDate: Date;
    onPick: (date: Date) => void;
}) {
    const t = useTranslations('Calendar');
    const inputRef = useRef<HTMLInputElement>(null);

    const jump = (key: string) => {
        const ms = parseCalendarDate(key);
        if (Number.isNaN(ms)) return;
        onPick(new Date(ms));
        onOpenChange(false);
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent className="max-w-xs gap-4">
                <DialogHeader>
                    <DialogTitle className="text-base">{t('goToDate')}</DialogTitle>
                </DialogHeader>
                <form
                    onSubmit={(e) => {
                        e.preventDefault();
                        jump(inputRef.current?.value ?? '');
                    }}
                    className="flex flex-col gap-3"
                >
                    <input
                        ref={inputRef}
                        type="date"
                        defaultValue={dayKeyOf(initialDate)}
                        className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm tabular-nums text-foreground outline-none focus-visible:ring-2 focus-visible:ring-brand/40"
                    />
                    <div className="flex items-center justify-end gap-2">
                        <Button type="button" variant="secondary" size="sm" onClick={() => jump(dayKeyOf(new Date()))}>
                            {t('today')}
                        </Button>
                        <Button type="submit" size="sm">
                            {t('goToDateGo')}
                        </Button>
                    </div>
                </form>
            </DialogContent>
        </Dialog>
    );
}
