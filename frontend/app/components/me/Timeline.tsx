import { headers } from "next/headers";
import { getMyWorkspacesFromCookie } from "@/app/lib/api";
import { type Activity, type Contact, type ContactLifecycleHistoryEntry, type Deal, type Note, type PersonCampaignTouch, type RecordComment, type Task, type UserReference } from "@/app/lib/types";
import TimelineContent from "./TimelineContent";

export type TimelineProps = {
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
    noteTarget?: { type: "person" | "deal" | "user"; id: number };
};

export default async function Timeline(props: TimelineProps) {
    const cookie = (await headers()).get("cookie");
    const workspaceState = await getMyWorkspacesFromCookie(cookie);
    return <TimelineContent
        key={`${workspaceState.activeWorkspaceId}:${props.noteTarget?.type}:${props.noteTarget?.id}`}
        {...props}
        originWorkspaceId={workspaceState.activeWorkspaceId}
    />;
}
