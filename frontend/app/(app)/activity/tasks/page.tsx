import { headers } from "next/headers";
import { redirect } from "next/navigation";
import PermissionsUnavailablePage from "@/app/components/PermissionsUnavailablePage";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import {
    getCurrentUserResultFromCookie,
    getEffectivePermissionsResultFromCookie,
    getMyWorkspacesFromCookie,
} from "@/app/lib/api";
import TasksBrowser from "@/app/components/activity/tasks/TasksBrowser";

export default async function TasksPage() {
    const cookie = (await headers()).get('cookie');
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
    if (!user) {
        redirect('/auth/login');
    }

    const [permissionsResult, workspaceState] = await Promise.all([
        getEffectivePermissionsResultFromCookie(cookie),
        getMyWorkspacesFromCookie(cookie),
    ]);
    if (!permissionsResult.ok) return <PermissionsUnavailablePage />;

    return (
        <TasksBrowser
            key={workspaceState.activeWorkspaceId}
            currentUserId={user.id}
            canDeleteTasks={permissionsResult.data.includes('TASK_DELETE')}
            originWorkspaceId={workspaceState.activeWorkspaceId}
        />
    );
}
