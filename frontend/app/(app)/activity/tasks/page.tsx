import { headers } from "next/headers";
import { redirect } from "next/navigation";
import PermissionsUnavailablePage from "@/app/components/PermissionsUnavailablePage";
import {
    getContacts,
    getCurrentUserFromCookie,
    getDeals,
    getEffectivePermissionsResultFromCookie,
    getMyWorkspacesFromCookie,
    getTasks,
    getUsers,
} from "@/app/lib/api";
import TasksBrowser from "@/app/components/activity/tasks/TasksBrowser";

export default async function TasksPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect('/auth/login');
    }

    const init = { headers: { cookie: cookie ?? '' }, cache: 'no-store' as const };

    const [tasks, persons, deals, users, permissionsResult, workspaceState] = await Promise.all([
        getTasks(init),
        getContacts({}, init),
        getDeals(init),
        getUsers(init),
        getEffectivePermissionsResultFromCookie(cookie),
        getMyWorkspacesFromCookie(cookie),
    ]);
    if (!permissionsResult.ok) return <PermissionsUnavailablePage />;

    return (
        <TasksBrowser
            tasks={tasks}
            persons={persons}
            deals={deals}
            users={users}
            currentUserId={user.id}
            canDeleteTasks={permissionsResult.data.includes('TASK_DELETE')}
            originWorkspaceId={workspaceState.activeWorkspaceId}
        />
    );
}
