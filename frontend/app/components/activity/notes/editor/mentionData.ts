import {
    getActiveWorkspaceMembers,
    getCompanies,
    getDeals,
    search as globalSearch,
} from "@/app/lib/api";
import type {
    Company,
    Contact,
    Deal,
    SearchResults,
    User,
    WorkspaceMember,
} from "@/app/lib/types";

export type MentionType = "user" | "person" | "deal" | "company";
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
    "#": ["deal", "company"],
};

const MAX_SUGGESTIONS = 8;

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
        label: member.displayName || member.username,
        sublabel: `@${member.username}`,
        avatarUrl: member.profilePictureUrl ?? null,
    };
}

function userItem(user: User): MentionItem {
    return {
        type: "user",
        id: user.id,
        label: user.displayName || user.username,
        sublabel: `@${user.username}`,
        avatarUrl: user.profilePictureUrl ?? null,
    };
}

function personItem(person: Contact): MentionItem {
    return {
        type: "person",
        id: person.id,
        label: person.name,
        sublabel: person.title || person.company?.name || "Contact",
        avatarUrl: person.imageUrl || null,
    };
}

function dealItem(deal: Deal): MentionItem {
    return { type: "deal", id: deal.id, label: deal.name, sublabel: "Deal", avatarUrl: null };
}

function companyItem(company: Company): MentionItem {
    return {
        type: "company",
        id: company.id,
        label: company.name,
        sublabel: company.industry || "Company",
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

function fromSearchResults(results: SearchResults): MentionItem[] {
    return [
        ...results.users.map(userItem),
        ...results.people.map(personItem),
        ...results.deals.map(dealItem),
        ...results.companies.map(companyItem),
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
