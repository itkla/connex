import { parseMysqlDateTime } from '@/app/lib/utils';

const DAY_MS = 86_400_000;

/** Runway kept between a scheduled follow-up and the date warmth is predicted to reach cold. */
const BUFFER_DAYS = 5;

/** Horizon used when warmth carries no predicted cold date at all. */
const FALLBACK_DAYS = 3;

/**
 * Picks a follow-up due date that lands a few days before the relationship is predicted to go cold,
 * clamped to no earlier than tomorrow. Falls back to a short horizon when there is no prediction, so
 * every signal-driven follow-up is dated ahead of the decay it answers.
 *
 * @param goesColdAt - the predicted cold date carried by warmth, or null when none was computed
 * @param now - the current time in epoch milliseconds
 * @returns the due date as `YYYY-MM-DD`, the shape the task composer and API accept
 */
export function followUpDueDate(goesColdAt: string | null | undefined, now: number): string {
    const cold = goesColdAt ? parseMysqlDateTime(goesColdAt) : NaN;
    const target = Math.max(
        Number.isNaN(cold) ? now + FALLBACK_DAYS * DAY_MS : cold - BUFFER_DAYS * DAY_MS,
        now + DAY_MS,
    );
    const date = new Date(target);
    const pad = (value: number) => String(value).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
