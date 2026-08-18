import type { ReactNode } from "react";
import type { useRouter } from "next/navigation";
import {
    BoltIcon,
    BriefcaseIcon,
    CheckCircleIcon,
    DocumentTextIcon,
    FunnelIcon,
    PaperClipIcon,
    TagIcon,
    UserIcon,
} from "@heroicons/react/24/outline";

import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import CompanyAvatar from "@/app/components/records/companies/CompanyAvatar";
import UserAvatar from "@/app/components/records/users/UserAvatar";
import { formatFileSize } from "@/app/lib/utils";
import { noteContentToPlainText } from "@/app/lib/references";
import type { SearchResults } from "@/app/lib/types";

/** A leading-icon component compatible with the sidebar/action icon contract. */
export type IconType = React.ComponentType<{ className?: string }>;

/**
 * One selectable search result. `leading` carries a pre-rendered avatar node when a type has one;
 * otherwise `icon` (or an `accent` swatch) is used. `index` is a flat position across all groups, used
 * by the roving-keyboard inline search dropdown; the cmdk palette ignores it and keys off `key`.
 */
export type ResultRow = {
    key: string;
    index: number;
    href: string;
    label: string;
    subtitle?: string;
    icon?: IconType;
    accent?: string;
    leading?: ReactNode;
    external?: boolean;
};

/** A labeled section of {@link ResultRow}s, e.g. all matching companies. */
export type ResultGroup = {
    key: string;
    heading: string;
    rows: ResultRow[];
};

/** Translator scoped to the `CommonSearchBar` namespace, used to resolve group headings. */
export type SearchTranslate = (key: string) => string;

/** Trims and ellipsizes free text so long note/task bodies stay to a single line. */
export function truncate(text: string, max = 70): string {
    const trimmed = text.trim();
    return trimmed.length > max ? `${trimmed.slice(0, max)}…` : trimmed;
}

/**
 * Builds the ordered, labeled result groups from a global-search response. This is the single source of
 * truth for result grouping, row shape, labels, and destination hrefs, shared by the global search
 * field, the `/search` page, and the command palette so record search is never reimplemented.
 *
 * @param results - the search response, or null when there is nothing to show
 * @param t - a translator scoped to the `CommonSearchBar` namespace
 * @returns the non-empty groups in canonical order
 */
export function buildSearchGroups(results: SearchResults | null, t: SearchTranslate): ResultGroup[] {
    if (!results) return [];

    let index = 0;
    const built: ResultGroup[] = [];

    const addGroup = <T,>(
        key: string,
        heading: string,
        items: T[] | undefined,
        toRow: (item: T) => Omit<ResultRow, "index">,
    ) => {
        if (!items?.length) return;
        built.push({
            key,
            heading,
            rows: items.map((item) => ({ ...toRow(item), index: index++ })),
        });
    };

    addGroup("users", t("groupUsers"), results.users, (u) => ({
        key: `user-${u.id}`,
        href: `/users/${u.id}`,
        leading: <UserAvatar user={u} type="small" />,
        label: u.displayName,
        subtitle: u.email || `@${u.username}`,
    }));
    addGroup("companies", t("groupCompanies"), results.companies, (c) => ({
        key: `company-${c.id}`,
        href: `/records/companies/${c.id}`,
        leading: <CompanyAvatar company={c} type="small" />,
        label: c.name,
        subtitle: c.industry || c.website || undefined,
    }));
    addGroup("people", t("groupPeople"), results.people, (p) => ({
        key: `person-${p.id}`,
        href: `/records/contacts/${p.id}`,
        leading: (
            <Avatar>
                <AvatarImage src={p.imageUrl || undefined} alt="" />
                <AvatarFallback>
                    <UserIcon className="size-4" />
                </AvatarFallback>
            </Avatar>
        ),
        label: p.name,
        subtitle: p.title || p.email || undefined,
    }));
    addGroup("deals", t("groupDeals"), results.deals, (d) => ({
        key: `deal-${d.id}`,
        href: `/records/deals/${d.id}`,
        icon: BriefcaseIcon,
        label: d.name,
        subtitle:
            typeof d.value === "number" ? `${d.currency} ${d.value.toLocaleString()}` : d.currency || undefined,
    }));
    addGroup("pipelines", t("groupPipelines"), results.pipelines, (p) => ({
        key: `pipeline-${p.id}`,
        href: "/records/pipelines",
        icon: FunnelIcon,
        label: p.name,
    }));
    addGroup("tags", t("groupTags"), results.tags, (tag) => ({
        key: `tag-${tag.id}`,
        href: "/library/tags",
        icon: TagIcon,
        label: tag.name,
        accent: tag.color || undefined,
    }));
    addGroup("activities", t("groupActivities"), results.activities, (a) => ({
        key: `activity-${a.id}`,
        href: `/activity/activities/${a.id}`,
        icon: BoltIcon,
        label: a.subject,
        subtitle: a.type || undefined,
    }));
    addGroup("notes", t("groupNotes"), results.notes, (n) => ({
        key: `note-${n.id}`,
        href: `/activity/notes/${n.id}`,
        icon: DocumentTextIcon,
        label: truncate(noteContentToPlainText(n.content)),
    }));
    addGroup("tasks", t("groupTasks"), results.tasks, (task) => ({
        key: `task-${task.id}`,
        href: `/activity/tasks/${task.id}`,
        icon: CheckCircleIcon,
        label: truncate(noteContentToPlainText(task.description)),
    }));
    addGroup("attachments", t("groupAttachments"), results.attachments, (a) => ({
        key: `attachment-${a.id}`,
        href: `/library/files?file=${a.id}`,
        icon: PaperClipIcon,
        label: a.fileName,
        subtitle: typeof a.size === "number" ? formatFileSize(a.size) : undefined,
    }));

    return built;
}

/**
 * Navigates to a result's destination, opening external URLs in a new tab and pushing in-app routes.
 *
 * @param router - the Next.js router
 * @param href - the destination path or URL
 * @param external - whether to open in a new browser tab
 */
export function openResult(router: ReturnType<typeof useRouter>, href: string, external = false): void {
    if (external) {
        window.open(href, "_blank", "noopener,noreferrer");
    } else {
        router.push(href);
    }
}
