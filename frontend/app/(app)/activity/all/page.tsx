import { headers } from "next/headers";
import { redirect } from "next/navigation";
import {
    getActivities,
    getContacts,
    getCurrentUserFromCookie,
    getDeals,
    getMyWorkspacesFromCookie,
    getUsers,
} from "@/app/lib/api";
import ActivitiesBrowser from "@/app/components/activity/activities/ActivitiesBrowser";

export default async function ActivityPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect('/auth/login');
    }

    const init = { headers: { cookie: cookie ?? '' }, cache: 'no-store' as const };

    const [activities, persons, deals, users, workspaceState] = await Promise.all([
        getActivities(init),
        getContacts({}, init),
        getDeals(init),
        getUsers(init),
        getMyWorkspacesFromCookie(cookie),
    ]);

    return (
        <ActivitiesBrowser
            activities={activities}
            persons={persons}
            deals={deals}
            users={users}
            currentUserId={user.id}
            originWorkspaceId={workspaceState.activeWorkspaceId}
        />
    );
}
