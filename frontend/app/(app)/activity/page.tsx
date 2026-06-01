import { headers } from "next/headers";
import { redirect } from "next/navigation";
import {
    getCurrentUserFromCookie,
    getActivitiesFromCookie,
    getContactsFromCookie,
    getDealsFromCookie,
    getUsers,
} from "@/app/lib/api";
import type { User } from "@/app/lib/types";
import ActivitiesBrowser from "@/app/components/activity/activities/ActivitiesBrowser";

export default async function ActivityPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect('/auth/login');
    }

    const init = cookie ? { headers: { cookie }, cache: 'no-store' as const } : undefined;

    const [activities, persons, deals, users] = await Promise.all([
        getActivitiesFromCookie(cookie),
        getContactsFromCookie(cookie),
        getDealsFromCookie(cookie),
        (init ? getUsers(init) : Promise.resolve([])).catch(() => [] as User[]),
    ]);

    return (
        <ActivitiesBrowser
            activities={activities}
            persons={persons}
            deals={deals}
            users={users}
            currentUserId={user.id}
        />
    );
}
