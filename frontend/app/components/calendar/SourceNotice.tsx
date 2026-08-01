'use client';

import { useTranslations } from 'next-intl';
import { ExclamationTriangleIcon } from '@heroicons/react/24/outline';

/** The record collections the calendar merges into its event stream. */
export type CalendarSourceKey = 'activities' | 'tasks' | 'persons' | 'deals' | 'notes';

/** A source whose bounded read returned fewer records than the workspace holds. */
export type CalendarTruncation = {
    source: CalendarSourceKey;
    shown: number;
    total: number;
};

/**
 * Disclosure banner for a calendar built from incomplete inputs.
 *
 * The calendar merges five collections; when one fails, rendering the remaining four as
 * a normal calendar quietly asserts that nothing happened on the missing days. This
 * says so instead.
 *
 * Truncation is reported separately and more quietly, because it is expected on large
 * workspaces rather than a fault — but it is still reported, since the underlying
 * endpoints have no date filter and return their oldest records first, so a truncated
 * source hides the most recent items rather than the least relevant ones.
 * @param failed sources whose fetch failed outright
 * @param truncated sources cut short by the per-source cap
 */
export default function SourceNotice({
    failed,
    truncated,
}: {
    failed: ReadonlyArray<CalendarSourceKey>;
    truncated: ReadonlyArray<CalendarTruncation>;
}) {
    const t = useTranslations('Calendar');

    if (failed.length === 0 && truncated.length === 0) {
        return null;
    }

    const nameOf = (source: CalendarSourceKey) => t(`sourceNotice.source.${source}`);

    return (
        <div className="flex flex-col gap-2">
            {failed.length > 0 ? (
                <section
                    className="flex items-start gap-3 rounded-2xl border border-warning/40 bg-warning/5 p-4"
                    aria-labelledby="calendar-source-failure"
                >
                    <ExclamationTriangleIcon className="mt-0.5 size-5 shrink-0 text-warning" aria-hidden />
                    <div className="min-w-0">
                        <h2 id="calendar-source-failure" className="text-sm font-semibold text-foreground">
                            {t('sourceNotice.failedTitle')}
                        </h2>
                        <p className="mt-1 text-sm text-muted-foreground">
                            {t('sourceNotice.failedBody', {
                                sources: failed.map(nameOf).join(t('sourceNotice.separator')),
                            })}
                        </p>
                    </div>
                </section>
            ) : null}
            {truncated.map((entry) => (
                <p key={entry.source} className="text-xs text-muted-foreground">
                    {t('sourceNotice.truncated', {
                        source: nameOf(entry.source),
                        shown: entry.shown,
                        total: entry.total,
                    })}
                </p>
            ))}
        </div>
    );
}
