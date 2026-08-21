import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import { getMyWorkspacesFromCookie } from "@/app/lib/api";
import { type Activity, type Contact, type ContactLifecycleHistoryEntry, type Deal, type Note, type PersonCampaignTouch, type RecordComment, type Task, type UserReference } from "@/app/lib/types";
import { buildTimeline, entryAuthorId, entryId } from "./timelineEntries";
import TimelineDeepLinkFallback from "./TimelineDeepLinkFallback";
import TimelineRow from "./TimelineRow";

export default async function Timeline({
    tasks,
    activities,
    notes,
    users = [],
    persons = [],
    deals = [],
    lifecycleHistory = [],
    comments = [],
    campaignTouches = [],
    currentUserId,
    companyId,
    limit,
}: {
    tasks: Task[];
    activities: Activity[];
    notes: Note[];
    users?: UserReference[];
    persons?: Contact[];
    deals?: Deal[];
    lifecycleHistory?: ContactLifecycleHistoryEntry[];
    comments?: RecordComment[];
    campaignTouches?: PersonCampaignTouch[];
    currentUserId?: number;
    companyId?: number | null;
    limit?: number;
}) {
    const cookie = (await headers()).get("cookie");
    const [t, workspaceState] = await Promise.all([
        getTranslations("MeTimeline"),
        getMyWorkspacesFromCookie(cookie),
    ]);
    const entries = buildTimeline({ tasks, activities, notes, lifecycleHistory, comments, campaignTouches });
    const visible = limit ? entries.slice(0, limit) : entries;
    const available = {
        task: visible.flatMap((entry) => entry.kind === "task" ? [entry.task.id] : []),
        activity: visible.flatMap((entry) => entry.kind === "activity" ? [entry.activity.id] : []),
        note: visible.flatMap((entry) => entry.kind === "note" ? [entry.note.id] : []),
    };

    const userById = new Map(users.map((u) => [u.id, u]));

    return (
        <>
            <TimelineDeepLinkFallback available={available} />
            {visible.length === 0 ? (
                <p className="px-6 py-12 text-center text-sm text-muted-foreground">
                    {t("emptyState")}
                </p>
            ) : (
                <ul className="divide-y divide-border">
                    {visible.map((entry) => {
                        const authorId = entryAuthorId(entry);
                        const author = authorId != null ? userById.get(authorId) : undefined;
                        return (
                            <TimelineRow
                                key={`${entry.kind}-${entryId(entry)}`}
                                entry={entry}
                                author={author}
                                persons={persons}
                                deals={deals}
                                currentUserId={currentUserId}
                                companyId={companyId ?? null}
                                originWorkspaceId={workspaceState.activeWorkspaceId}
                            />
                        );
                    })}
                </ul>
            )}
        </>
    );
}
