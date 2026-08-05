import {
    getActiveWorkspaceMembers,
    getAllAttachments,
    getCompanies,
    getDeals,
    search as globalSearch,
} from "@/app/lib/api";
import type {
    Activity,
    Attachment,
    Company,
    Contact,
    Deal,
    Note,
    SearchResults,
    Task,
    User,
    WorkspaceMember,
} from "@/app/lib/types";
import { noteSnippet } from "@/app/lib/noteText";

export type MentionType = "user" | "person" | "deal" | "company" | "note" | "file" | "task" | "activity";
export type MentionTrigger = "@" | "#";

export type MentionItem = {
    type: MentionType;
    id: number;
    label: string;
    sublabel: string;
    avatarUrl: string | null;
};

export const TRIGGER_TYPES: Record<MentionTrigger, readonly MentionType[]> = {
    "@": ["user", "person"],
    "#": ["deal", "company", "note", "file", "task", "activity"],
};

const MAX_SUGGESTIONS = 8;

function safeLabel(value: string): string {
    return value.replace(/[[\]]/g, "").replace(/[\r\n]+/g, " ").trim();
}

function workspaceKey(): string {
    if (typeof document === "undefined") return "";
    const match = document.cookie.match(/(?:^|;\s*)connex_workspace=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : "";
}

let cacheKey = "";
let membersPromise: Promise<MentionItem[]> | null = null;
let recordsPromise: Promise<MentionItem[]> | null = null;
let filesPromise: Promise<MentionItem[]> | null = null;

function resetWhenWorkspaceChanged(): void {
    const key = workspaceKey();
    if (key !== cacheKey) {
        cacheKey = key;
        membersPromise = null;
        recordsPromise = null;
        filesPromise = null;
    }
}

function memberItem(member: WorkspaceMember): MentionItem {
    return {
        type: "user",
        id: member.id,
        label: safeLabel(member.displayName || member.username),
        sublabel: `@${member.username}`,
        avatarUrl: member.profilePictureUrl ?? null,
    };
}

function userItem(user: User): MentionItem {
    return {
        type: "user",
        id: user.id,
        label: safeLabel(user.displayName || user.username),
        sublabel: `@${user.username}`,
        avatarUrl: user.profilePictureUrl ?? null,
    };
}

function personItem(person: Contact): MentionItem {
    return {
        type: "person",
        id: person.id,
        label: safeLabel(person.name),
        sublabel: person.title || person.company?.name || "Contact",
        avatarUrl: person.imageUrl || null,
    };
}

function dealItem(deal: Deal): MentionItem {
    return { type: "deal", id: deal.id, label: safeLabel(deal.name), sublabel: "Deal", avatarUrl: null };
}

function companyItem(company: Company): MentionItem {
    return {
        type: "company",
        id: company.id,
        label: safeLabel(company.name),
        sublabel: company.industry || "Company",
        avatarUrl: null,
    };
}

function noteItem(note: Note): MentionItem {
    const title =
        note.title?.trim() || note.content.split("\n").find((line) => line.trim().length > 0) || "Untitled";
    return { type: "note", id: note.id, label: safeLabel(title), sublabel: "Note", avatarUrl: null };
}

function fileItem(file: Attachment): MentionItem {
    return { type: "file", id: file.id, label: safeLabel(file.fileName), sublabel: "File", avatarUrl: null };
}

function taskItem(task: Task): MentionItem {
    return {
        type: "task",
        id: task.id,
        label: safeLabel(noteSnippet(task.description, 80)) || "Task",
        sublabel: "Task",
        avatarUrl: null,
    };
}

function activityItem(activity: Activity): MentionItem {
    return {
        type: "activity",
        id: activity.id,
        label: safeLabel(activity.subject) || "Activity",
        sublabel: "Activity",
        avatarUrl: null,
    };
}

function membersPool(): Promise<MentionItem[]> {
    resetWhenWorkspaceChanged();
    if (!membersPromise) {
        membersPromise = getActiveWorkspaceMembers()
            .then((members) => members.map(memberItem))
            .catch(() => []);
    }
    return membersPromise;
}

function recordsPool(): Promise<MentionItem[]> {
    resetWhenWorkspaceChanged();
    if (!recordsPromise) {
        recordsPromise = Promise.all([getCompanies(), getDeals()])
            .then(([companies, deals]) => [
                ...companies.map(companyItem),
                ...deals.map(dealItem),
            ])
            .catch(() => []);
    }
    return recordsPromise;
}

function filesPool(): Promise<MentionItem[]> {
    resetWhenWorkspaceChanged();
    if (!filesPromise) {
        filesPromise = getAllAttachments()
            .then((files) => files.map(fileItem))
            .catch(() => []);
    }
    return filesPromise;
}

function fromSearchResults(results: SearchResults): MentionItem[] {
    return [
        ...results.users.map(userItem),
        ...results.people.map(personItem),
        ...results.deals.map(dealItem),
        ...results.companies.map(companyItem),
        ...results.notes.map(noteItem),
        ...results.attachments.map(fileItem),
        ...results.tasks.map(taskItem),
        ...results.activities.map(activityItem),
    ];
}

function matches(item: MentionItem, needle: string): boolean {
    const haystack = `${item.label} ${item.sublabel}`.toLowerCase();
    return haystack.includes(needle);
}

/**
 * Resolve mention suggestions for a trigger and query, honoring the trigger's
 * allowed entity types and excluding the current author from `@` mentions.
 */
export async function queryMentions(
    trigger: MentionTrigger,
    query: string,
    excludeUserId?: number,
): Promise<MentionItem[]> {
    const allowed = TRIGGER_TYPES[trigger];
    const needle = query.trim().toLowerCase();
    const pool = trigger === "@" ? await membersPool() : await recordsPool();
    const local = pool.filter((item) => item.type !== "user" || item.id !== excludeUserId);

    if (!needle) {
        return local.filter((item) => allowed.includes(item.type)).slice(0, MAX_SUGGESTIONS);
    }

    const results = await globalSearch(query.trim()).catch(() => null);
    if (results) {
        const remote = fromSearchResults(results).filter(
            (item) =>
                allowed.includes(item.type) &&
                (item.type !== "user" || item.id !== excludeUserId),
        );
        if (remote.length) return remote.slice(0, MAX_SUGGESTIONS);
    }

    return local
        .filter((item) => allowed.includes(item.type) && matches(item, needle))
        .slice(0, MAX_SUGGESTIONS);
}

/**
 * Resolve file-attachment suggestions for the note editor's file-reference
 * picker. Empty queries return a short local pool; typed queries prefer global
 * search results scoped to attachments, falling back to the local pool.
 */
export async function queryFileMentions(query: string): Promise<MentionItem[]> {
    const needle = query.trim().toLowerCase();
    const pool = await filesPool();

    if (!needle) {
        return pool.slice(0, MAX_SUGGESTIONS);
    }

    const results = await globalSearch(query.trim()).catch(() => null);
    if (results) {
        const remote = results.attachments.map(fileItem);
        if (remote.length) return remote.slice(0, MAX_SUGGESTIONS);
    }

    return pool.filter((item) => matches(item, needle)).slice(0, MAX_SUGGESTIONS);
}
