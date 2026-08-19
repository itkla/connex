"use client";

import { ChevronRightIcon } from "@heroicons/react/24/outline";
import Link from "next/link";
import { usePathname } from "next/navigation";

import type { SettingsNavGroup, SettingsNavModel } from "@/app/lib/settingsNavigation";
import { cn } from "@/lib/utils";

/**
 * The destinations a group holds today, listed under it. Rendered only for a group that owns more
 * than one, because a group with a single destination would restate its own row.
 */
function GroupDestinations({ group, pathname }: { group: SettingsNavGroup; pathname: string }) {
    if (group.destinations.length < 2) return null;
    return (
        <ul className="mt-0.5 ml-3 space-y-0.5 border-l border-border pl-3">
            {group.destinations.map((destination) => {
                const current = pathname === destination.href;
                return (
                    <li key={destination.id}>
                        <Link
                            href={destination.href}
                            aria-current={current ? "page" : undefined}
                            className={cn(
                                "-mx-2 block px-2 py-1 text-sm outline-none transition-colors duration-(--motion-micro) hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-brand/40 motion-reduce:transition-none",
                                current ? "font-medium text-brand-dark" : "text-muted-foreground",
                            )}
                        >
                            {destination.title}
                        </Link>
                    </li>
                );
            })}
        </ul>
    );
}

/**
 * The wide-viewport settings directory: every authorization scope as a labeled section, every group
 * as a row into the destination it owns today, and the destinations themselves listed beneath a
 * group that holds several.
 *
 * Groups are the rows because a group is what #1340 makes a destination; the nested lists are what
 * that destination contains until the routes move, so the reader can see the consolidation and
 * still reach the page they already know by name. Rows are full-bleed list rows rather than
 * buttons: this is navigation, and the row that matches the current route says so with
 * `aria-current` instead of with a raised surface.
 *
 * @param scopes - the resolved navigation
 */
export default function SettingsDirectory({ scopes }: { scopes: SettingsNavModel }) {
    const pathname = usePathname() ?? "";
    return (
        <div className="flex flex-col gap-12">
            {scopes.map((scope) => (
                <section key={scope.anchor} id={scope.anchor} className="scroll-mt-24">
                    <h2 className="text-sm font-semibold text-foreground">
                        {scope.name}
                        {scope.qualifier ? (
                            <span className="font-normal text-muted-foreground"> · {scope.qualifier}</span>
                        ) : null}
                    </h2>
                    <ul className="mt-3 gap-x-10 [&>li]:mb-1 [&>li]:break-inside-avoid xl:columns-2 2xl:columns-3">
                        {scope.groups.map((group) => {
                            const current = pathname === group.href;
                            return (
                                <li key={group.id}>
                                    <Link
                                        href={group.href}
                                        aria-current={current ? "page" : undefined}
                                        className={cn(
                                            "group/row -mx-3 flex items-center justify-between gap-4 px-3 py-2 outline-none transition-colors duration-(--motion-micro) hover:bg-muted focus-visible:ring-2 focus-visible:ring-brand/40 motion-reduce:transition-none",
                                            current && "bg-muted",
                                        )}
                                    >
                                        <span
                                            className={cn(
                                                "min-w-0 truncate text-sm font-medium",
                                                current ? "text-brand-dark" : "text-foreground",
                                            )}
                                        >
                                            {group.title}
                                        </span>
                                        <ChevronRightIcon
                                            aria-hidden
                                            className="size-4 shrink-0 text-muted-foreground opacity-0 transition-opacity duration-(--motion-micro) group-hover/row:opacity-100 group-focus-visible/row:opacity-100 motion-reduce:transition-none"
                                        />
                                    </Link>
                                    <GroupDestinations group={group} pathname={pathname} />
                                </li>
                            );
                        })}
                    </ul>
                </section>
            ))}
        </div>
    );
}
