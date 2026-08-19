"use client";

import { useMemo } from "react";
import Link from "next/link";
import { useTranslations } from "next-intl";
import { BoltIcon, MagnifyingGlassIcon } from "@heroicons/react/24/outline";
import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import { PageShell } from "@/app/components/PageShell";
import { buildSearchGroups } from "@/app/lib/search/resultGroups";
import type { SearchResults } from "@/app/lib/types";
import { useActions, useRegisterActions } from "@/app/hooks/useActions";
import type { AppAction } from "@/app/lib/actions/types";
import { Button } from "@/components/ui/button";

export default function SearchResultsView({
    query,
    results,
}: {
    query: string;
    results: SearchResults;
}) {
    const t = useTranslations("CommonSearchBar");
    const to = useTranslations("WorkflowOperations");
    const { run } = useActions();

    const actions = useMemo<readonly AppAction[]>(() => [{
        id: "record.run-search-workflow",
        group: "record",
        labelKey: "record.runWorkflow",
        descriptionKey: "description.record.runWorkflow",
        icon: BoltIcon,
        order: 21,
        execute: (_context, helpers) => {
            const resolvedScope = { kind: "search_snapshot" as const, query };
            helpers.openOverlay({
                kind: "workflow-manual-run",
                sourceSurface: helpers.source === "palette" ? "command_palette" : "search",
                recordType: null,
                scope: helpers.source === "palette"
                    ? { kind: "command_palette", resolvedScope }
                    : resolvedScope,
            });
        },
    }], [query]);
    useRegisterActions(actions);

    const groups = buildSearchGroups(results, t);
    const hasResults = groups.length > 0;

    return (
        <PageShell>
                <Rise delay={0}>
                    <PageHeader
                        className="px-4 sm:px-6"
                        variant="compact"
                        title={t("resultsHeading", { query })}
                        actions={(
                            <Button variant="outline" onClick={() => void run("record.run-search-workflow", { source: "menu" })}>
                                <BoltIcon className="size-4" />
                                {to("manual.title")}
                            </Button>
                        )}
                    />
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
