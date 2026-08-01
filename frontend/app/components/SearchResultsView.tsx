"use client";

import Link from "next/link";
import { useTranslations } from "next-intl";
import { MagnifyingGlassIcon } from "@heroicons/react/24/outline";
import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import { PageShell } from "@/app/components/PageShell";
import { buildSearchGroups } from "@/app/lib/search/resultGroups";
import type { SearchResults } from "@/app/lib/types";

export default function SearchResultsView({
    query,
    results,
}: {
    query: string;
    results: SearchResults;
}) {
    const t = useTranslations("CommonSearchBar");

    const groups = buildSearchGroups(results, t);
    const hasResults = groups.length > 0;

    return (
        <PageShell tier="wide">
                <Rise delay={0}>
                    <PageHeader className="px-4 sm:px-6" title={t("resultsHeading", { query })} />
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
        </PageShell>
    );
}
