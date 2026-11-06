import { headers } from "next/headers";
import { redirect } from "next/navigation";
import {
    getCurrentUserResultFromCookie,
    getMyWorkspacesFromCookie,
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

    const workspaceState = await getMyWorkspacesFromCookie(cookie);

    return (
        <ActivitiesBrowser
            key={workspaceState.activeWorkspaceId}
            currentUserId={user.id}
            originWorkspaceId={workspaceState.activeWorkspaceId}
        />
    );
}
