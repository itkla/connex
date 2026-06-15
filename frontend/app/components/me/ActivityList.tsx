// NOTE: not used in /me page anymore, but might be used in other pages so im keeping it

import { getLocale, getTranslations } from "next-intl/server";

import { type Activity } from "@/app/lib/types";
import EmptyState from "./EmptyState";
import { timeOf, formatShortDate } from "@/app/lib/utils";

export default async function ActivityList({ activities }: { activities: Activity[] }) {
    const t = await getTranslations("MeActivityList");
    const locale = await getLocale();

    if (activities.length === 0) {
        return <EmptyState message={t("empty")} />;
    }

    const sorted = [...activities].sort(
        (a, b) => timeOf(b.timestamp) - timeOf(a.timestamp),
    );
    const recent = sorted.slice(0, 5);

    return (
        <ul className="divide-y divide-border">
            {recent.map((activity) => (
                <li key={activity.id} className="flex flex-col gap-1 px-6 py-3">
                    <div className="flex items-start justify-between gap-4">
                        <span className="text-sm text-foreground">{activity.subject}</span>
                        {activity.timestamp ? (
                            <span className="shrink-0 text-xs text-muted-foreground">
                                {formatShortDate(activity.timestamp, locale)}
                            </span>
                        ) : null}
                    </div>
                    <span className="text-xs tracking-wide text-muted-foreground uppercase">
                        {activity.type}
                    </span>
                </li>
            ))}
        </ul>
    );
}