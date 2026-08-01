import CalendarShell from '@/app/components/calendar/CalendarShell';
import type { CalendarSourceKey, CalendarTruncation } from '@/app/components/calendar/SourceNotice';
import {
    getActivitiesCappedResultFromCookie,
    getContactsCappedResultFromCookie,
    getContactTemperaturesResultFromCookie,
    getCurrentUserFromCookie,
    getDealsCappedResultFromCookie,
    getNotesCappedResultFromCookie,
    getTasksCappedResultFromCookie,
    type CappedItems,
    type CookieResult,
} from '@/app/lib/api';
import { headers } from 'next/headers';
import { redirect } from 'next/navigation';

/**
 * Folds one bounded source read into the calendar's rendering inputs, collecting the
 * failure and truncation flags the shell discloses rather than letting either pass as a
 * quiet empty result.
 */
function collect<T>(
    source: CalendarSourceKey,
    result: CookieResult<CappedItems<T>>,
    failed: CalendarSourceKey[],
    truncated: CalendarTruncation[],
): T[] {
    if (!result.ok) {
        failed.push(source);
        return [];
    }
    if (result.data.truncated) {
        truncated.push({ source, shown: result.data.items.length, total: result.data.total });
    }
    return result.data.items;
}

export default async function CalendarPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect('/auth/login');
    }

    const [activitiesResult, tasksResult, personsResult, dealsResult, notesResult] = await Promise.all([
        getActivitiesCappedResultFromCookie(cookie),
        getTasksCappedResultFromCookie(cookie),
        getContactsCappedResultFromCookie(cookie),
        getDealsCappedResultFromCookie(cookie),
        getNotesCappedResultFromCookie(cookie),
    ]);

    const failed: CalendarSourceKey[] = [];
    const truncated: CalendarTruncation[] = [];
    const activities = collect('activities', activitiesResult, failed, truncated);
    const tasks = collect('tasks', tasksResult, failed, truncated);
    const persons = collect('persons', personsResult, failed, truncated);
    const deals = collect('deals', dealsResult, failed, truncated);
    const notes = collect('notes', notesResult, failed, truncated);

    const temperaturesResult = await getContactTemperaturesResultFromCookie(
        cookie,
        persons.map((person) => person.id),
    );
    const temperatures = temperaturesResult.ok ? temperaturesResult.data : [];

    return (
        <CalendarShell
            activities={activities}
            tasks={tasks}
            persons={persons}
            deals={deals}
            notes={notes}
            temperatures={temperatures}
            currentUserId={user.id}
            failedSources={failed}
            truncatedSources={truncated}
            warmthFailed={!temperaturesResult.ok}
        />
    );
}
