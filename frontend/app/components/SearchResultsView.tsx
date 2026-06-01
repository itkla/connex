"use client";

import Link from "next/link";
import { useTranslations } from "next-intl";
import {
    UserIcon,
    BriefcaseIcon,
    FunnelIcon,
    TagIcon,
    BoltIcon,
    DocumentTextIcon,
    CheckCircleIcon,
} from "@heroicons/react/24/outline";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import CompanyAvatar from "@/app/components/records/companies/CompanyAvatar";
import UserAvatar from "@/app/components/records/users/UserAvatar";
import type { SearchResults } from "@/app/lib/types";

type IconType = React.ComponentType<{ className?: string }>;

type Row = {
    key: string;
    href: string;
    label: string;
    subtitle?: string;
    icon?: IconType;
    accent?: string;
    leading?: React.ReactNode;
};

type Group = { key: string; heading: string; rows: Row[] };

function truncate(text: string, max = 120): string {
    const trimmed = text.trim();
    return trimmed.length > max ? `${trimmed.slice(0, max)}…` : trimmed;
}

export default function SearchResultsView({
    query,
    results,
}: {
    query: string;
    results: SearchResults;
}) {
    const t = useTranslations("CommonSearchBar");

    const groups: Group[] = [];
    const add = <T,>(
        key: string,
        heading: string,
        items: T[] | undefined,
        toRow: (item: T) => Row,
    ) => {
        if (items?.length) groups.push({ key, heading, rows: items.map(toRow) });
    };

    // TODO: reorder the groups based on relevancy, rather than hardcoded order
    add("users", t("groupUsers"), results.users, (u) => ({
        key: `user-${u.id}`,
        href: `/users/${u.id}`,
        leading: <UserAvatar user={u} type="small" />,
        label: u.displayName,
        subtitle: u.email || `@${u.username}`,
    }));
    add("companies", t("groupCompanies"), results.companies, (c) => ({
        key: `company-${c.id}`,
        href: `/records/companies/${c.id}`,
        leading: <CompanyAvatar company={c} type="small" />,
        label: c.name,
        subtitle: c.industry || c.website || undefined,
    }));
    add("people", t("groupPeople"), results.people, (p) => ({
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
    add("deals", t("groupDeals"), results.deals, (d) => ({
        key: `deal-${d.id}`,
        href: `/records/deals/${d.id}`,
        icon: BriefcaseIcon,
        label: d.name,
        subtitle:
            typeof d.value === "number"
                ? `${d.currency} ${d.value.toLocaleString()}`
                : d.currency || undefined,
    }));
    add("pipelines", t("groupPipelines"), results.pipelines, (p) => ({
        key: `pipeline-${p.id}`,
        href: `/records/pipelines/${p.id}`,
        icon: FunnelIcon,
        label: p.name,
    }));
    add("tags", t("groupTags"), results.tags, (tag) => ({
        key: `tag-${tag.id}`,
        href: "/library/tags",
        icon: TagIcon,
        label: tag.name,
        accent: tag.color || undefined,
    }));
    add("activities", t("groupActivities"), results.activities, (a) => ({
        key: `activity-${a.id}`,
        href: "/activity",
        icon: BoltIcon,
        label: a.subject,
        subtitle: a.type || undefined,
    }));
    add("notes", t("groupNotes"), results.notes, (n) => ({
        key: `note-${n.id}`,
        href: "/activity/notes",
        icon: DocumentTextIcon,
        label: truncate(n.content),
    }));
    add("tasks", t("groupTasks"), results.tasks, (task) => ({
        key: `task-${task.id}`,
        href: "/activity/tasks",
        icon: CheckCircleIcon,
        label: truncate(task.description),
    }));

    const hasResults = groups.length > 0;

    return (
        <div className="mx-auto w-full max-w-3xl">
            <h1 className="mb-6 text-xl font-semibold text-foreground">
                {t("resultsHeading", { query })}
            </h1>

            {!hasResults ? (
                <p className="text-sm text-neutral-500">{t("noResults", { query })}</p>
            ) : (
                <div className="space-y-8">
                    {groups.map((group) => (
                        <section key={group.key}>
                            <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-neutral-400">
                                {group.heading}{" "}
                                <span className="text-neutral-300">({group.rows.length})</span>
                            </h2>
                            <ul className="overflow-hidden rounded-2xl ring-1 ring-black/5">
                                {group.rows.map((row) => {
                                    const Icon = row.icon;
                                    return (
                                        <li key={row.key} className="border-b border-black/5 last:border-0">
                                            <Link
                                                href={row.href}
                                                className="flex items-center gap-3 bg-white px-4 py-3 transition hover:bg-neutral-50"
                                            >
                                                <span className="flex size-8 shrink-0 items-center justify-center">
                                                    {row.leading ? (
                                                        row.leading
                                                    ) : row.accent ? (
                                                        <span
                                                            className="size-4 rounded-full ring-1 ring-black/10"
                                                            style={{ backgroundColor: row.accent }}
                                                        />
                                                    ) : Icon ? (
                                                        <Icon className="size-5 text-neutral-400" />
                                                    ) : null}
                                                </span>
                                                <span className="min-w-0 flex-1">
                                                    <span className="block truncate text-sm text-black">
                                                        {row.label}
                                                    </span>
                                                    {row.subtitle && (
                                                        <span className="block truncate text-xs text-neutral-500">
                                                            {row.subtitle}
                                                        </span>
                                                    )}
                                                </span>
                                            </Link>
                                        </li>
                                    );
                                })}
                            </ul>
                        </section>
                    ))}
                </div>
            )}
        </div>
    );
}