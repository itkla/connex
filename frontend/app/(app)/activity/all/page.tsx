import { headers } from "next/headers";
import { redirect } from "next/navigation";
import {
    getActivities,
    getContacts,
    getCurrentUserResultFromCookie,
    getDeals,
    getMyWorkspacesFromCookie,
    getUsers,
} from "@/app/lib/api";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import ActivitiesBrowser from "@/app/components/activity/activities/ActivitiesBrowser";

export default async function ActivityPage() {
    const cookie = (await headers()).get('cookie');
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
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
