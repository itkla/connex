import {
    getActiveWorkspaceMembers,
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

export type MentionLabels = Partial<Record<MentionType | "untitled", string>>;

export const TRIGGER_TYPES: Record<MentionTrigger, readonly MentionType[]> = {
    "@": ["user", "person"],
    "#": ["deal", "company", "note", "file", "task", "activity"],
};

const MAX_SUGGESTIONS = 8;
const DEFAULT_LABELS: Record<MentionType | "untitled", string> = {
    user: "User",
    person: "Contact",
    deal: "Deal",
    company: "Company",
    note: "Note",
    file: "File",
    task: "Task",
    activity: "Activity",
    untitled: "Untitled",
};

function labelFor(labels: MentionLabels | undefined, type: MentionType | "untitled"): string {
    return labels?.[type] ?? DEFAULT_LABELS[type];
}

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

function resetWhenWorkspaceChanged(): void {
    const key = workspaceKey();
    if (key !== cacheKey) {
        cacheKey = key;
        membersPromise = null;
        recordsPromise = null;
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

function personItem(person: Contact, labels?: MentionLabels): MentionItem {
    return {
        type: "person",
        id: person.id,
        label: safeLabel(person.name),
        sublabel: person.title || person.company?.name || labelFor(labels, "person"),
        avatarUrl: person.imageUrl || null,
    };
}

function dealItem(deal: Deal, labels?: MentionLabels): MentionItem {
    return { type: "deal", id: deal.id, label: safeLabel(deal.name), sublabel: labelFor(labels, "deal"), avatarUrl: null };
}

function companyItem(company: Company, labels?: MentionLabels): MentionItem {
    return {
        type: "company",
        id: company.id,
        label: safeLabel(company.name),
        sublabel: company.industry || labelFor(labels, "company"),
        avatarUrl: null,
    };
}

function noteItem(note: Note, labels?: MentionLabels): MentionItem {
    const title =
        note.title?.trim() || note.content.split("\n").find((line) => line.trim().length > 0) || labelFor(labels, "untitled");
    return { type: "note", id: note.id, label: safeLabel(title), sublabel: labelFor(labels, "note"), avatarUrl: null };
}

function fileItem(file: Attachment, labels?: MentionLabels): MentionItem {
    return { type: "file", id: file.id, label: safeLabel(file.fileName), sublabel: labelFor(labels, "file"), avatarUrl: null };
}

function taskItem(task: Task, labels?: MentionLabels): MentionItem {
    return {
        type: "task",
        id: task.id,
        label: safeLabel(noteSnippet(task.description, 80)) || labelFor(labels, "task"),
        sublabel: labelFor(labels, "task"),
        avatarUrl: null,
    };
}

function activityItem(activity: Activity, labels?: MentionLabels): MentionItem {
    return {
        type: "activity",
        id: activity.id,
        label: safeLabel(activity.subject) || labelFor(labels, "activity"),
        sublabel: labelFor(labels, "activity"),
        avatarUrl: null,
    };
}

function localizeItem(item: MentionItem, labels?: MentionLabels): MentionItem {
    const fallback = DEFAULT_LABELS[item.type];
    if (item.sublabel !== fallback) return item;
    return { ...item, sublabel: labelFor(labels, item.type) };
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
                ...companies.map((company) => companyItem(company)),
                ...deals.map((deal) => dealItem(deal)),
            ])
            .catch(() => []);
    }
    return recordsPromise;
}

function fromSearchResults(results: SearchResults, labels?: MentionLabels): MentionItem[] {
    return [
        ...results.users.map(userItem),
        ...results.people.map((person) => personItem(person, labels)),
        ...results.deals.map((deal) => dealItem(deal, labels)),
        ...results.companies.map((company) => companyItem(company, labels)),
        ...results.notes.map((note) => noteItem(note, labels)),
        ...results.attachments.map((file) => fileItem(file, labels)),
        ...results.tasks.map((task) => taskItem(task, labels)),
        ...results.activities.map((activity) => activityItem(activity, labels)),
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
    labels?: MentionLabels,
): Promise<MentionItem[]> {
    const allowed = TRIGGER_TYPES[trigger];
    const needle = query.trim().toLowerCase();
    const pool = trigger === "@" ? await membersPool() : await recordsPool();
    const local = pool
        .map((item) => localizeItem(item, labels))
        .filter((item) => item.type !== "user" || item.id !== excludeUserId);

    if (!needle) {
        return local.filter((item) => allowed.includes(item.type)).slice(0, MAX_SUGGESTIONS);
    }

    const results = await globalSearch(query.trim()).catch(() => null);
    if (results) {
        const remote = fromSearchResults(results, labels).filter(
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
