import { headers } from "next/headers";
import { redirect } from "next/navigation";
import {
    getCurrentUserFromCookie,
    getTasksFromCookie,
    getContactsFromCookie,
    getDealsFromCookie,
    getEffectivePermissionsFromCookie,
    getUsers,
} from "@/app/lib/api";
import type { User } from "@/app/lib/types";
import TasksBrowser from "@/app/components/activity/tasks/TasksBrowser";

export default async function TasksPage() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);
    if (!user) {
        redirect('/auth/login');
    }

    const init = cookie ? { headers: { cookie }, cache: 'no-store' as const } : undefined;

    const [tasks, persons, deals, users, effectivePermissions] = await Promise.all([
        getTasksFromCookie(cookie),
        getContactsFromCookie(cookie),
        getDealsFromCookie(cookie),
        (init ? getUsers(init) : Promise.resolve([])).catch(() => [] as User[]),
        getEffectivePermissionsFromCookie(cookie),
    ]);

    return (
        <TasksBrowser
            tasks={tasks}
            persons={persons}
            deals={deals}
            users={users}
            currentUserId={user.id}
            canDeleteTasks={effectivePermissions.includes('TASK_DELETE')}
        />
    );
}
