import CalendarShell from '@/app/components/calendar/CalendarShell';
import { getContactsFromCookie, getDealsFromCookie, getCurrentUserFromCookie, getNotesFromCookie, getActivitiesFromCookie, getTasksFromCookie, getContactTemperaturesFromCookie } from '@/app/lib/api';
import { headers } from 'next/headers';
import { redirect } from 'next/navigation';

export default async function CalendarPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect('/auth/login');
    }

    const [activities, tasks, persons, deals, notes, temperatures] = await Promise.all([
        getActivitiesFromCookie(cookie),
        getTasksFromCookie(cookie),
        getContactsFromCookie(cookie),
        getDealsFromCookie(cookie),
        getNotesFromCookie(cookie),
        getContactTemperaturesFromCookie(cookie),
    ]);
    return (
        <CalendarShell
            activities={activities}
            tasks={tasks}
            persons={persons}
            deals={deals}
            notes={notes}
            temperatures={temperatures}
            currentUserId={user.id}
        />
    );
}