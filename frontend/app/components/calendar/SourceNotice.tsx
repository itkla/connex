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
 * Sources the backend returns newest-first, so a truncated read keeps the most recent
 * records and drops older ones — the only case where a directional "older ones may be
 * missing" claim is accurate. Every other source is ordered alphabetically (persons),
 * by board position (deals) or by ascending due date (tasks), where no recency claim
 * holds, so those get a neutral count-only disclosure.
 */
const NEWEST_FIRST: ReadonlyArray<CalendarSourceKey> = ['activities', 'notes'];

/**
 * Disclosure banner for a calendar built from incomplete inputs.
 *
 * The calendar merges five collections; when one fails, rendering the remaining four as
 * a normal calendar quietly asserts that nothing happened on the missing days. This
 * says so instead.
 *
 * Truncation is reported per source with wording that matches that source's actual
 * ordering: a blanket "oldest loaded first" claim would be false for the newest-first
 * sources and unfounded for the alphabetical and board-ordered ones, so newest-first
 * sources get an accurate "older ones may be missing" note and the rest a neutral
 * count-only one.
 * @param failed sources whose fetch failed outright
 * @param truncated sources cut short by the per-source cap
 * @param warmthFailed whether contact-warmth colours could not be loaded
 */
export default function SourceNotice({
    failed,
    truncated,
    warmthFailed = false,
}: {
    failed: ReadonlyArray<CalendarSourceKey>;
    truncated: ReadonlyArray<CalendarTruncation>;
    warmthFailed?: boolean;
}) {
    const t = useTranslations('Calendar');

    if (failed.length === 0 && truncated.length === 0 && !warmthFailed) {
        return null;
    }

    const nameOf = (source: CalendarSourceKey) => t(`sourceNotice.source.${source}`);

    return (
        <div className="flex flex-col gap-2">
            {failed.length > 0 || warmthFailed ? (
                <section
                    className="flex items-start gap-3 rounded-2xl border border-warning/40 bg-warning/5 p-4"
                    aria-labelledby="calendar-source-failure"
                >
                    <ExclamationTriangleIcon className="mt-0.5 size-5 shrink-0 text-warning" aria-hidden />
                    <div className="min-w-0">
                        <h2 id="calendar-source-failure" className="text-sm font-semibold text-foreground">
                            {t('sourceNotice.failedTitle')}
                        </h2>
                        {failed.length > 0 ? (
                            <p className="mt-1 text-sm text-muted-foreground">
                                {t('sourceNotice.failedBody', {
                                    sources: failed.map(nameOf).join(t('sourceNotice.separator')),
                                })}
                            </p>
                        ) : null}
                        {warmthFailed ? (
                            <p className="mt-1 text-sm text-muted-foreground">
                                {t('sourceNotice.warmthFailed')}
                            </p>
                        ) : null}
                    </div>
                </section>
            ) : null}
            {truncated.map((entry) => (
                <p key={entry.source} className="text-xs text-muted-foreground">
                    {t(
                        NEWEST_FIRST.includes(entry.source)
                            ? 'sourceNotice.truncatedRecent'
                            : 'sourceNotice.truncatedNeutral',
                        {
                            source: nameOf(entry.source),
                            shown: entry.shown,
                            total: entry.total,
                        },
                    )}
                </p>
            ))}
        </div>
    );
}
