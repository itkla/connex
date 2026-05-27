import { getTasksFromCookie, getCurrentUserFromCookie } from "@/app/lib/api";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";

const cookie = (await headers()).get('cookie');
const user = await getCurrentUserFromCookie(cookie);
if (!user) {
    redirect('/auth/login');
}

export default async function TasksPage() {
    const t = await getTranslations("ActivityTasks");
    const allTasks = await getTasksFromCookie(cookie);
    console.log(allTasks);
    const userTasks = allTasks.filter((task) => task.assignedToId === user?.id);
    return (
        <div>
            <h1>{t("title")}</h1>
            <h2>{t("myTasks")}</h2>
            <ul>
                {userTasks.map((task) => (
                    <li key={task.id}>{task.description}</li>
                ))}
            </ul>
            <h2>{t("allTasks")}</h2>
            <ul>
                {allTasks.map((task) => (
                    <li key={task.id}>{task.description}</li>
                ))}
            </ul>
        </div>
    );
}
