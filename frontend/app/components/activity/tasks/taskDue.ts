import { parseMysqlDateTime } from '@/app/lib/utils';

/** Relative urgency of a task's due date, driving its chip color. */
export type DueTone = 'overdue' | 'today' | 'soon' | 'later';

/** Tailwind classes for the due-date chip, keyed by {@link DueTone}. */
export const DUE_CHIP: Record<DueTone, string> = {
    overdue: 'bg-red-50 text-red-600 ring-red-600/10 dark:bg-red-950/40 dark:text-red-400 dark:ring-red-400/20',
    today: 'bg-brand-light/70 text-brand-dark ring-brand-dark/15',
    soon: 'bg-muted text-muted-foreground ring-border',
    later: 'bg-muted text-muted-foreground ring-border',
};

/**
 * Formats a task due date into a short relative label and urgency tone. Returns null when the date
 * is absent or unparseable. Shared by the tasks list and the tasks Kanban board.
 * @param dueDate the MySQL datetime string, or undefined
 * @param t the ActivityTasks translator (for dueYesterday/dueToday/dueTomorrow)
 * @param locale the active locale for the fallback short date
 */
export function formatDue(
    dueDate: string | undefined,
    t: (key: string) => string,
    locale: string,
): { label: string; tone: DueTone } | null {
    if (!dueDate) return null;
    const ts = parseMysqlDateTime(dueDate);
    if (Number.isNaN(ts)) return null;
    const date = new Date(ts);
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const diffDays = Math.floor((date.getTime() - today.getTime()) / (24 * 60 * 60 * 1000));
    if (diffDays < 0) {
        const label = diffDays === -1 ? t('dueYesterday') : new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric' }).format(date);
        return { label, tone: 'overdue' };
    }
    if (diffDays === 0) return { label: t('dueToday'), tone: 'today' };
    if (diffDays === 1) return { label: t('dueTomorrow'), tone: 'soon' };
    return {
        label: new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric' }).format(date),
        tone: diffDays <= 6 ? 'soon' : 'later',
    };
}
