"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { MagnifyingGlassIcon } from "@heroicons/react/24/outline";
import { Loader2Icon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { search as searchApi } from "@/app/lib/api";
import type { SearchResults } from "@/app/lib/types";
import { cn } from "@/lib/utils";
import { buildSearchGroups, openResult, type ResultGroup } from "@/app/lib/search/resultGroups";

const MIN_QUERY_LENGTH = 2;
const DEBOUNCE_MS = 250;

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
        /* eslint-disable react-hooks/set-state-in-effect */
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

    const groups = useMemo<ResultGroup[]>(() => buildSearchGroups(results, t), [results, t]);

    const flatRows = useMemo(() => groups.flatMap((g) => g.rows), [groups]);
    const hasResults = flatRows.length > 0;

    // keep the highlighted row visible as the user arrows through results.
    useEffect(() => {
        if (activeIndex < 0) return;
        const el = listRef.current?.querySelector(`[data-index="${activeIndex}"]`);
        el?.scrollIntoView({ block: "nearest" });
    }, [activeIndex]);

    function navigate(href: string, external = false) {
        setOpen(false);
        setActiveIndex(-1);
        openResult(router, href, external);
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
                navigate(active.href, active.external);
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
                    className="w-full rounded-full bg-muted py-2.5 pr-4 pl-11 text-base text-foreground placeholder:text-muted-foreground outline-none ring-1 ring-border transition focus:ring-2 focus:ring-brand"
                    role="combobox"
                    aria-expanded={showDropdown}
                    aria-controls="search-results-listbox"
                    aria-autocomplete="list"
                    autoComplete="off"
                />
                <Button
                    type="submit"
                    className="absolute left-3 top-1/2 flex -translate-y-1/2 items-center justify-center border-none bg-transparent p-0 hover:bg-transparent"
                    tabIndex={-1}
                    aria-label={t("search")}
                >
                    <MagnifyingGlassIcon className="size-5 text-muted-foreground" />
                </Button>
            </form>

            {showDropdown && (
                <div
                    ref={listRef}
                    id="search-results-listbox"
                    className="absolute left-0 right-0 top-full z-50 mt-2 max-h-[70vh] overflow-y-auto rounded-2xl bg-popover p-2 text-popover-foreground shadow-lg ring-1 ring-border"
                    role="listbox"
                >
                    {loading && !hasResults && (
                        <Loader2Icon className="size-5 text-muted-foreground animate-spin flex justify-center items-center" />
                    )}

                    {!loading && !hasResults && (
                        <p className="px-3 py-2 text-sm text-muted-foreground">
                            {t("noResults", { query: trimmed })}
                        </p>
                    )}

                    {groups.map((group) => (
                        <div key={group.key} className="mb-1 last:mb-0">
                            <p className="px-3 pb-1 pt-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                                {group.heading}
                            </p>
                            {group.rows.map((row) => {
                                const Icon = row.icon;
                                return (
                                    <button
                                        key={row.key}
                                        type="button"
                                        data-index={row.index}
                                        onClick={() => navigate(row.href, row.external)}
                                        onMouseEnter={() => setActiveIndex(row.index)}
                                        className={cn(
                                            "flex w-full items-center gap-3 rounded-xl px-3 py-2 text-left transition",
                                            activeIndex === row.index ? "bg-muted" : "hover:bg-muted",
                                        )}
                                        role="option"
                                        aria-selected={activeIndex === row.index}
                                    >
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
                                            <span className="block truncate text-sm text-foreground">{row.label}</span>
                                            {row.subtitle && (
                                                <span className="block truncate text-xs text-muted-foreground">
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
