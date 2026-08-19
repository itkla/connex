"use client";

import { useEffect, useState } from "react";

import type { SettingsNavModel } from "@/app/lib/settingsNavigation";
import { topmostIntersecting, type ObservedSection } from "@/app/lib/settingsScopeSpy";
import { cn } from "@/lib/utils";

/**
 * The sticky table of contents beside the settings directory: one row per authorization scope, with
 * the row for the scope currently in view marked as the reader's location.
 *
 * Scopes, not groups: the directory beside it already lists every group, and repeating them here
 * would be two lists of the same thing. This answers "which scope am I reading" while the reader
 * scrolls, and grows into the persistent settings navigation once the destinations move under
 * `/settings/*` and the column has children to stay beside.
 *
 * @param scopes - the resolved navigation
 * @param label - the accessible name for the navigation landmark
 */
export default function SettingsScopeSpine({
    scopes,
    label,
    className,
}: {
    scopes: SettingsNavModel;
    label: string;
    className?: string;
}) {
    const [activeAnchor, setActiveAnchor] = useState<string | null>(scopes[0]?.anchor ?? null);

    useEffect(() => {
        const sections = scopes
            .map((scope) => document.getElementById(scope.anchor))
            .filter((element): element is HTMLElement => element !== null);
        if (sections.length === 0) return;

        const observed = new Map<string, ObservedSection>();
        const observer = new IntersectionObserver(
            (entries) => {
                for (const entry of entries) {
                    observed.set(entry.target.id, {
                        id: entry.target.id,
                        isIntersecting: entry.isIntersecting,
                        top: entry.boundingClientRect.top,
                    });
                }
                setActiveAnchor((current) => topmostIntersecting([...observed.values()]) ?? current);
            },
            { rootMargin: "-96px 0px -60% 0px", threshold: 0 },
        );

        sections.forEach((section) => observer.observe(section));
        return () => observer.disconnect();
    }, [scopes]);

    if (scopes.length === 0) return null;

    return (
        <nav aria-label={label} className={cn("text-sm", className)}>
            <ul className="border-l border-border">
                {scopes.map((scope) => {
                    const active = activeAnchor === scope.anchor;
                    return (
                        <li key={scope.anchor}>
                            <a
                                href={`#${scope.anchor}`}
                                aria-current={active ? "location" : undefined}
                                className={cn(
                                    "-ml-px block border-l-2 py-1.5 pl-4 transition-colors duration-(--motion-micro) ease-calm motion-reduce:transition-none",
                                    active
                                        ? "border-brand font-medium text-brand-dark"
                                        : "border-transparent text-muted-foreground hover:border-border hover:text-foreground",
                                )}
                            >
                                <span className="block truncate">{scope.name}</span>
                                {scope.qualifier ? (
                                    <span className="block truncate text-xs text-muted-foreground">
                                        {scope.qualifier}
                                    </span>
                                ) : null}
                            </a>
                        </li>
                    );
                })}
            </ul>
        </nav>
    );
}
