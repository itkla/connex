"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import {
    MagnifyingGlassIcon,
    UserIcon,
    BriefcaseIcon,
    FunnelIcon,
    TagIcon,
    BoltIcon,
    DocumentTextIcon,
    CheckCircleIcon,
} from "@heroicons/react/24/outline";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import CompanyAvatar from "@/app/components/records/companies/CompanyAvatar";
import { search as searchApi } from "@/app/lib/api";
import type { SearchResults } from "@/app/lib/types";
import { cn } from "@/lib/utils";

const MIN_QUERY_LENGTH = 2;
const DEBOUNCE_MS = 250;

type IconType = React.ComponentType<{ className?: string }>;

type ResultRow = {
    key: string;
    index: number;
    href: string;
    label: string;
    subtitle?: string;
    icon?: IconType;
    accent?: string;
    leading?: React.ReactNode;
};

type ResultGroup = {
    key: string;
    heading: string;
    rows: ResultRow[];
};

function truncate(text: string, max = 70): string {
    const trimmed = text.trim();
    return trimmed.length > max ? `${trimmed.slice(0, max)}…` : trimmed;
}

export default function SearchBar() {
    const t = useTranslations("CommonSearchBar");
    const router = useRouter();
    const searchParams = useSearchParams();
    const urlQuery = searchParams.get("query") ?? "";

    const [query, setQuery] = useState(urlQuery);
    const [results, setResults] = useState<SearchResults | null>(null);
    const [loading, setLoading] = useState(false);
    const [open, setOpen] = useState(false);
    const [activeIndex, setActiveIndex] = useState(-1);

    const containerRef = useRef<HTMLDivElement>(null);
    const listRef = useRef<HTMLDivElement>(null);

    const trimmed = query.trim();
    const showDropdown = open && trimmed.length >= MIN_QUERY_LENGTH;

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect
        if (urlQuery) setQuery(urlQuery);
    }, [urlQuery]);

    useEffect(() => {
        if (!open || trimmed.length < MIN_QUERY_LENGTH) {
            setResults(null);
            setLoading(false);
            return;
        }

        setLoading(true);
        /* eslint-enable react-hooks/set-state-in-effect */
        const controller = new AbortController();
        const timer = setTimeout(() => {
            searchApi(trimmed, { signal: controller.signal })
                .then((data) => {
                    setResults(data);
                    setActiveIndex(-1);
                    setLoading(false);
                })
                .catch(() => {
                    if (controller.signal.aborted) return;
                    setResults(null);
                    setLoading(false);
                });
        }, DEBOUNCE_MS);

        return () => {
            controller.abort();
            clearTimeout(timer);
        };
    }, [trimmed, open]);

    // Dismiss when clicking outside the search bar + dropdown.
    useEffect(() => {
        if (!open) return;
        function onPointerDown(event: MouseEvent) {
            if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
                setOpen(false);
            }
        }
        document.addEventListener("mousedown", onPointerDown);
        return () => document.removeEventListener("mousedown", onPointerDown);
    }, [open]);

    const groups = useMemo<ResultGroup[]>(() => {
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
                typeof d.value === "number"
                    ? `${d.currency} ${d.value.toLocaleString()}`
                    : d.currency || undefined,
        }));
        addGroup("pipelines", t("groupPipelines"), results.pipelines, (p) => ({
            key: `pipeline-${p.id}`,
            href: `/records/pipelines/${p.id}`,
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
            href: "/activity",
            icon: BoltIcon,
            label: a.subject,
            subtitle: a.type || undefined,
        }));
        addGroup("notes", t("groupNotes"), results.notes, (n) => ({
            key: `note-${n.id}`,
            href: "/activity/notes",
            icon: DocumentTextIcon,
            label: truncate(n.content),
        }));
        addGroup("tasks", t("groupTasks"), results.tasks, (task) => ({
            key: `task-${task.id}`,
            href: "/activity/tasks",
            icon: CheckCircleIcon,
            label: truncate(task.description),
        }));

        return built;
    }, [results, t]);

    const flatRows = useMemo(() => groups.flatMap((g) => g.rows), [groups]);
    const hasResults = flatRows.length > 0;

    // keep the highlighted row visible as the user arrows through results.
    useEffect(() => {
        if (activeIndex < 0) return;
        const el = listRef.current?.querySelector(`[data-index="${activeIndex}"]`);
        el?.scrollIntoView({ block: "nearest" });
    }, [activeIndex]);

    function navigate(href: string) {
        setOpen(false);
        setActiveIndex(-1);
        router.push(href);
    }

    function goToSearchPage() {
        if (!trimmed) return;
        setOpen(false);
        setActiveIndex(-1);
        router.push(`/search?query=${encodeURIComponent(trimmed)}`);
    }

    function onKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
        if (event.key === "Escape") {
            setOpen(false);
            return;
        }

        if (event.key === "Enter") {
            event.preventDefault();
            const active = showDropdown && hasResults && activeIndex >= 0 ? flatRows[activeIndex] : undefined;
            if (active) {
                navigate(active.href);
            } else {
                goToSearchPage();
            }
            return;
        }

        if (!showDropdown || !hasResults) return;

        if (event.key === "ArrowDown") {
            event.preventDefault();
            setActiveIndex((i) => Math.min(i + 1, flatRows.length - 1));
        } else if (event.key === "ArrowUp") {
            event.preventDefault();
            setActiveIndex((i) => Math.max(i - 1, 0));
        }
    }

    return (
        <div ref={containerRef} className="relative flex-1">
            <form
                className="relative"
                onSubmit={(e) => {
                    e.preventDefault();
                    goToSearchPage();
                }}
                role="search"
            >
                <input
                    type="text"
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    onFocus={() => setOpen(true)}
                    onKeyDown={onKeyDown}
                    placeholder={t("placeholder")}
                    className="w-full rounded-full bg-neutral-100 px-4 py-2.5 pr-10 text-base text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand"
                    role="combobox"
                    aria-expanded={showDropdown}
                    aria-controls="search-results-listbox"
                    aria-autocomplete="list"
                    autoComplete="off"
                />
                <Button
                    type="submit"
                    className="absolute right-2 top-1/2 -translate-y-1/2 p-0 bg-transparent border-none flex items-center justify-center hover:bg-transparent"
                    tabIndex={-1}
                    aria-label={t("search")}
                >
                    <MagnifyingGlassIcon className="size-5 text-neutral-500" />
                </Button>
            </form>

            {showDropdown && (
                <div
                    ref={listRef}
                    id="search-results-listbox"
                    className="absolute left-0 right-0 top-full z-50 mt-2 max-h-[70vh] overflow-y-auto rounded-2xl bg-white p-2 text-black shadow-lg ring-1 ring-black/5"
                    role="listbox"
                >
                    {loading && !hasResults && (
                        <p className="px-3 py-2 text-sm text-neutral-500">{t("searching")}</p>
                    )}

                    {!loading && !hasResults && (
                        <p className="px-3 py-2 text-sm text-neutral-500">
                            {t("noResults", { query: trimmed })}
                        </p>
                    )}

                    {groups.map((group) => (
                        <div key={group.key} className="mb-1 last:mb-0">
                            <p className="px-3 pb-1 pt-2 text-xs font-semibold uppercase tracking-wide text-neutral-400">
                                {group.heading}
                            </p>
                            {group.rows.map((row) => {
                                const Icon = row.icon;
                                return (
                                    <button
                                        key={row.key}
                                        type="button"
                                        data-index={row.index}
                                        onClick={() => navigate(row.href)}
                                        onMouseEnter={() => setActiveIndex(row.index)}
                                        className={cn(
                                            "flex w-full items-center gap-3 rounded-xl px-3 py-2 text-left transition",
                                            activeIndex === row.index ? "bg-neutral-100" : "hover:bg-neutral-50",
                                        )}
                                        role="option"
                                        aria-selected={activeIndex === row.index}
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
                                            <span className="block truncate text-sm text-black">{row.label}</span>
                                            {row.subtitle && (
                                                <span className="block truncate text-xs text-neutral-500">
                                                    {row.subtitle}
                                                </span>
                                            )}
                                        </span>
                                    </button>
                                );
                            })}
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
