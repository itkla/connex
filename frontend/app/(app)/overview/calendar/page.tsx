import Calendar from '@/app/components/calendar/Calendar';
import { getActivities, getTasks, getContacts, getDeals, getNotes, getContactsFromCookie, getDealsFromCookie, getCurrentUserFromCookie, getNotesFromCookie, getActivitiesFromCookie, getTasksFromCookie } from '@/app/lib/api';
import { headers } from 'next/headers';
import { redirect } from 'next/navigation';

export default async function CalendarPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect('/auth/login');
    }

    const [activities, tasks, persons, deals, notes] = await Promise.all([
        getActivitiesFromCookie(cookie),
        getTasksFromCookie(cookie),
        getContactsFromCookie(cookie),
        getDealsFromCookie(cookie),
        getNotesFromCookie(cookie),
    ]);
    return <Calendar activities={activities} tasks={tasks} persons={persons} deals={deals} notes={notes} />;
}