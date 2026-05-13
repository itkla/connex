import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getCurrentUserFromCookie, getActivitiesFromCookie } from "@/app/lib/api";

const cookie = (await headers()).get('cookie');
const user = await getCurrentUserFromCookie(cookie);
if (!user) {
    redirect('/auth/login');
}

export default async function ActivityPage() {
    const allActivities = await getActivitiesFromCookie(cookie);
    return (
        <div>
            <h1>Activity</h1>
            <h2>All Activities</h2>
            <ul>
                {allActivities.map((activity) => (
                    <li key={activity.id}>{activity.type} - {activity.subject} - {activity.timestamp}</li>
                ))}
            </ul>
        </div>
    )
}