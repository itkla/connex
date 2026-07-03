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
    PaperClipIcon,
    MagnifyingGlassIcon,
} from "@heroicons/react/24/outline";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import CompanyAvatar from "@/app/components/records/companies/CompanyAvatar";
import UserAvatar from "@/app/components/records/users/UserAvatar";
import Rise from "@/app/components/motion/Rise";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import { noteContentToPlainText } from "@/app/lib/references";
import type { SearchResults } from "@/app/lib/types";
import { formatFileSize } from "@/app/lib/utils";

type IconType = React.ComponentType<{ className?: string }>;

type Row = {
    key: string;
    href: string;
    label: string;
    subtitle?: string;
    icon?: IconType;
    accent?: string;
    leading?: React.ReactNode;
    external?: boolean;
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
        href: "/activity/all",
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
        label: truncate(noteContentToPlainText(task.description)),
    }));
    add("attachments", t("groupAttachments"), results.attachments, (a) => ({
        key: `attachment-${a.id}`,
        href: `/library/files?file=${a.id}`,
        icon: PaperClipIcon,
        label: a.fileName,
        subtitle: typeof a.size === "number" ? formatFileSize(a.size) : undefined,
    }));

    const hasResults = groups.length > 0;

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-7xl flex-col gap-10">
                <Rise delay={0}>
                    <header className="px-4 sm:px-6">
                        <h1 className="text-2xl font-semibold tracking-tight text-foreground">
                            {t("resultsHeading", { query })}
                        </h1>
                    </header>
                </Rise>

                {!hasResults ? (
                    <Rise delay={0.06}>
                        <div className="flex flex-col items-center justify-center gap-2 rounded-2xl border border-dashed border-border bg-card px-6 py-16 text-center">
                            <MagnifyingGlassIcon className="size-6 text-muted-foreground" aria-hidden />
                            <p className="text-sm text-muted-foreground">
                                {t("noResults", { query })}
                            </p>
                        </div>
                    </Rise>
                ) : (
                    groups.map((group, groupIndex) => (
                        <Rise key={group.key} delay={0.06 + groupIndex * 0.06}>
                            <section>
                                <SectionHeader
                                    title={group.heading}
                                    action={
                                        <span className="px-3 text-xs text-muted-foreground">
                                            {group.rows.length}
                                        </span>
                                    }
                                />
                                <ul className="overflow-hidden rounded-2xl border border-border">
                                    {group.rows.map((row) => {
                                        const Icon = row.icon;
                                        const rowClassName =
                                            "flex items-center gap-3 bg-card px-4 py-3 transition hover:bg-muted";
                                        const content = (
                                            <>
                                                <span className="flex size-8 shrink-0 items-center justify-center">
                                                    {row.leading ? (
                                                        row.leading
                                                    ) : row.accent ? (
                                                        <span
                                                            className="size-4 rounded-full ring-1 ring-border"
                                                            style={{ backgroundColor: row.accent }}
                                                        />
                                                    ) : Icon ? (
                                                        <Icon className="size-5 text-muted-foreground" />
                                                    ) : null}
                                                </span>
                                                <span className="min-w-0 flex-1">
                                                    <span className="block truncate text-sm text-foreground">
                                                        {row.label}
                                                    </span>
                                                    {row.subtitle && (
                                                        <span className="block truncate text-xs text-muted-foreground">
                                                            {row.subtitle}
                                                        </span>
                                                    )}
                                                </span>
                                            </>
                                        );
                                        return (
                                            <li key={row.key} className="border-b border-border last:border-0">
                                                {row.external ? (
                                                    <a
                                                        href={row.href}
                                                        target="_blank"
                                                        rel="noopener noreferrer"
                                                        className={rowClassName}
                                                    >
                                                        {content}
                                                    </a>
                                                ) : (
                                                    <Link href={row.href} className={rowClassName}>
                                                        {content}
                                                    </Link>
                                                )}
                                            </li>
                                        );
                                    })}
                                </ul>
                            </section>
                        </Rise>
                    ))
                )}
            </div>
        </div>
    );
}