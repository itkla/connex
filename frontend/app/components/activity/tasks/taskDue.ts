import { formatShortDate, parseCalendarDateUtc } from '@/app/lib/utils';
import { calendarDayInTimeZone } from '@/app/lib/timeZone';

/** Relative urgency of a task's due date, driving its chip color. */
export type DueTone = 'overdue' | 'today' | 'soon' | 'later';

/** Tailwind classes for the due-date chip, keyed by {@link DueTone}. */
export const DUE_CHIP: Record<DueTone, string> = {
    overdue: 'bg-destructive/10 text-destructive ring-destructive/15',
    today: 'bg-brand-light/70 text-brand-text ring-brand-dark/15',
    soon: 'bg-muted text-muted-foreground ring-border',
    later: 'bg-muted text-muted-foreground ring-border',
};

/**
 * Formats a task due date into a short relative label and urgency tone. Returns null when the date
 * is absent or unparseable. Shared by the tasks list and the tasks Kanban board.
 * @param dueDate the calendar date, or undefined
 * @param t the ActivityTasks translator (for dueYesterday/dueToday/dueTomorrow)
 * @param locale the active locale for the fallback short date
 */
export function formatDue(
    dueDate: string | undefined,
    t: (key: string) => string,
    locale: string,
    now: number,
    timeZone: string,
): { label: string; tone: DueTone } | null {
    if (!dueDate) return null;
    const ts = parseCalendarDateUtc(dueDate);
    if (Number.isNaN(ts)) return null;
    const today = parseCalendarDateUtc(calendarDayInTimeZone(now, timeZone));
    const diffDays = Math.round((ts - today) / (24 * 60 * 60 * 1000));
    if (diffDays < 0) {
        const label = diffDays === -1 ? t('dueYesterday') : formatShortDate(dueDate, locale);
        return { label, tone: 'overdue' };
    }
    if (diffDays === 0) return { label: t('dueToday'), tone: 'today' };
    if (diffDays === 1) return { label: t('dueTomorrow'), tone: 'soon' };
    return {
        label: formatShortDate(dueDate, locale),
        tone: diffDays <= 6 ? 'soon' : 'later',
    };
}
