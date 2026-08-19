"use client";

import { ChevronRightIcon, MagnifyingGlassIcon } from "@heroicons/react/24/outline";
import Link from "next/link";

import { EmptyState } from "@/app/components/EmptyState";
import type { SettingsNavSearchResult } from "@/app/lib/settingsNavigation";
import { Button } from "@/components/ui/button";

/**
 * What settings search found, as a flat list in navigation order: the destination's own name, and
 * the scope and group that place it.
 *
 * Flat rather than grouped on purpose. A reader who searched has already named the thing they want;
 * re-imposing the hierarchy on two or three hits would make them read it twice.
 *
 * The results are a filtered list, not an autocomplete: they are links to routes, not options in a
 * popup. So the field stays an ordinary search input pointing at this region with `aria-controls`,
 * and the home owns the live region that announces the count — `role="combobox"` without a
 * `listbox` and `option` roles beneath it would be invalid ARIA and read worse than this does.
 *
 * @param results - the matching destinations
 * @param label - the accessible name for the results list
 * @param emptyTitle - the no-results heading, already interpolated
 * @param emptyBody - the no-results explanation
 * @param clearLabel - the label of the control that clears the search
 * @param onClear - clears the search
 */
export default function SettingsSearchResults({
    results,
    label,
    emptyTitle,
    emptyBody,
    clearLabel,
    onClear,
}: {
    results: readonly SettingsNavSearchResult[];
    label: string;
    emptyTitle: string;
    emptyBody: string;
    clearLabel: string;
    onClear: () => void;
}) {
    return (
        <>
            {results.length === 0 ? (
                <EmptyState
                    tone="muted"
                    variant="inline"
                    icon={MagnifyingGlassIcon}
                    title={emptyTitle}
                    body={emptyBody}
                    action={
                        <Button variant="outline" onClick={onClear}>
                            {clearLabel}
                        </Button>
                    }
                />
            ) : (
                <ul aria-label={label} className="-mx-3 space-y-1">
                    {results.map((result) => (
                        <li key={result.id}>
                            <Link
                                href={result.href}
                                className="group/result flex items-center justify-between gap-4 px-3 py-2 outline-none transition-colors duration-(--motion-micro) hover:bg-muted focus-visible:ring-2 focus-visible:ring-brand/40 motion-reduce:transition-none"
                            >
                                <span className="min-w-0">
                                    <span className="block truncate text-sm font-medium text-foreground">
                                        {result.title}
                                    </span>
                                    <span className="block truncate text-xs text-muted-foreground">
                                        {result.scopeLabel} · {result.groupTitle}
                                    </span>
                                </span>
                                <ChevronRightIcon
                                    aria-hidden
                                    className="size-4 shrink-0 text-muted-foreground opacity-0 transition-opacity duration-(--motion-micro) group-hover/result:opacity-100 group-focus-visible/result:opacity-100 motion-reduce:transition-none"
                                />
                            </Link>
                        </li>
                    ))}
                </ul>
            )}
        </>
    );
}
