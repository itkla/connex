"use client";

import { useEffect, useMemo, useState, type ComponentType } from "react";
import { useLocale, useTranslations } from "next-intl";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import {
    ClipboardDocumentListIcon,
    FunnelIcon,
} from "@heroicons/react/24/outline";
import {
    PlusIcon,
    PencilSquareIcon,
    TrashIcon,
    ArrowRightEndOnRectangleIcon,
    ArrowLeftStartOnRectangleIcon,
    EyeIcon,
    BoltIcon,
    ChevronDownIcon,
    ArrowRightIcon,
    ArrowUpIcon,
    ArrowDownIcon,
    UserPlusIcon,
    UserMinusIcon,
    TagIcon,
    ArrowPathIcon,
} from "@heroicons/react/20/solid";
import { Badge } from "@/components/ui/badge";
import { getAuditLogs } from "@/app/lib/api";
import Rise from "@/app/components/motion/Rise";
import {
    SearchField,
    FilterBar,
    MultiSelectFilter,
    RadioFilter,
    SortToggle,
    type FilterChipData,
} from "@/app/components/filters";
import { cn } from "@/lib/utils";
import {
    formatRelativeTime,
    formatShortDate,
    parseMysqlDateTime,
} from "@/app/lib/utils";
import { type AuditChange, type AuditLogEntry } from "@/app/lib/types";

type Tone = "create" | "update" | "delete" | "auth" | "view" | "default";

type VerbMeta = {
    tone: Tone;
    icon: ComponentType<{ className?: string }>;
    verbKey: string;
};

const VERB_META: Record<string, VerbMeta> = {
    create: { tone: "create", icon: PlusIcon, verbKey: "verbCreated" },
    update: { tone: "update", icon: PencilSquareIcon, verbKey: "verbUpdated" },
    delete: { tone: "delete", icon: TrashIcon, verbKey: "verbDeleted" },
    login: { tone: "auth", icon: ArrowRightEndOnRectangleIcon, verbKey: "verbLoggedIn" },
    logout: { tone: "auth", icon: ArrowLeftStartOnRectangleIcon, verbKey: "verbLoggedOut" },
    register: { tone: "auth", icon: UserPlusIcon, verbKey: "verbRegistered" },
    view: { tone: "view", icon: EyeIcon, verbKey: "verbViewed" },
    updateAvatar: { tone: "update", icon: PencilSquareIcon, verbKey: "verbUpdatedAvatar" },
    addTag: { tone: "update", icon: TagIcon, verbKey: "verbAddedTag" },
    removeTag: { tone: "update", icon: TagIcon, verbKey: "verbRemovedTag" },
    replaceTags: { tone: "update", icon: TagIcon, verbKey: "verbUpdatedTags" },
    addPerson: { tone: "update", icon: UserPlusIcon, verbKey: "verbAddedPerson" },
    removePerson: { tone: "update", icon: UserMinusIcon, verbKey: "verbRemovedPerson" },
    updatePersonRole: { tone: "update", icon: PencilSquareIcon, verbKey: "verbUpdatedRole" },
    replacePeople: { tone: "update", icon: UserPlusIcon, verbKey: "verbUpdatedPeople" },
};

const PLAIN_VERBS = new Set<string>(["login", "logout", "updateAvatar"]);

const TONE_DOT: Record<Tone, string> = {
    create: "bg-chart-won/12 text-chart-won",
    update: "bg-chart-2/12 text-chart-2",
    delete: "bg-destructive/12 text-destructive",
    auth: "bg-chart-4/12 text-chart-4",
    view: "bg-muted-foreground/12 text-muted-foreground",
    default: "bg-muted-foreground/12 text-muted-foreground",
};

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];
const DAY_MS = 86_400_000;
const SEARCH_KEYS: (keyof AuditLogEntry)[] = ["actorLabel", "targetLabel", "action", "summary"];
const NODE_ANCHOR = 18;
const DATE_ANCHOR = 18;
const PULSE_DAYS = 14;

type OutcomeFilter = "all" | "success" | "failed";
type RangeFilter = "all" | "today" | "7d" | "30d";
type Sort = "newest" | "oldest";
type FacetExcept = "query" | "verbs" | "entities" | "outcome" | "actors" | "range" | null;

type Filters = {
    query: string;
    verbs: Set<string>;
    entities: Set<string>;
    outcome: OutcomeFilter;
    actors: Set<string>;
    range: RangeFilter;
};

type FeedEntry =
    | { kind: "day"; id: string; label: string; count: number; connectUp: boolean; connectDown: boolean }
    | { kind: "row"; id: string; entry: AuditLogEntry; isLastInGroup: boolean; connectUp: boolean; connectDown: boolean };

function verbOf(action: string): string {
    const i = action.lastIndexOf(".");
    return i === -1 ? action : action.slice(i + 1);
}

function startOfLocalDay(ms: number): number {
    const d = new Date(ms);
    return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}

function rangeCutoff(range: RangeFilter, now: number | null): number {
    if (now == null) return -Infinity;
    if (range === "today") return startOfLocalDay(now);
    if (range === "7d") return now - 7 * DAY_MS;
    if (range === "30d") return now - 30 * DAY_MS;
    return -Infinity;
}

function isFailed(e: AuditLogEntry): boolean {
    return e.outcome != null && e.outcome !== "success";
}

export default function AuditLogBrowser({
    initialEntries,
    pageSize,
}: {
    initialEntries: AuditLogEntry[];
    pageSize: number;
}) {
    const t = useTranslations("AdminAuditLog");
    const locale = useLocale();
    const reduce = useReducedMotion() ?? false;

    const [entries, setEntries] = useState<AuditLogEntry[]>(initialEntries);
    const [hasMore, setHasMore] = useState<boolean>(initialEntries.length >= pageSize);
    const [loadingMore, setLoadingMore] = useState(false);
    const [loadError, setLoadError] = useState(false);

    async function loadMore() {
        if (loadingMore || !hasMore) return;
        setLoadingMore(true);
        setLoadError(false);
        try {
            const batch = await getAuditLogs({ limit: pageSize, offset: entries.length });
            setEntries((prev) => {
                const seen = new Set(prev.map((e) => e.id));
                const merged = [...prev];
                for (const e of batch) if (!seen.has(e.id)) merged.push(e);
                return merged;
            });
            setHasMore(batch.length >= pageSize);
        } catch {
            setLoadError(true);
        } finally {
            setLoadingMore(false);
        }
    }

    const [filters, setFilters] = useState<Filters>({
        query: "",
        verbs: new Set(),
        entities: new Set(),
        outcome: "all",
        actors: new Set(),
        range: "all",
    });
    const [sort, setSort] = useState<Sort>("newest");
    const [expanded, setExpanded] = useState<Set<number>>(() => new Set());

    const [now, setNow] = useState<number | null>(null);
    useEffect(() => {
        const raf = requestAnimationFrame(() => setNow(Date.now()));
        const id = setInterval(() => setNow(Date.now()), 60_000);
        return () => {
            cancelAnimationFrame(raf);
            clearInterval(id);
        };
    }, []);

    const systemLabel = t("actorSystem");

    const actorKeyOf = useMemo(
        () => (e: AuditLogEntry) => e.actorLabel ?? systemLabel,
        [systemLabel],
    );

    const verbTotal = useMemo(() => {
        const m = new Map<string, number>();
        for (const e of entries) {
            const v = verbOf(e.action);
            m.set(v, (m.get(v) ?? 0) + 1);
        }
        return m;
    }, [entries]);

    const entityTotal = useMemo(() => {
        const m = new Map<string, number>();
        for (const e of entries) if (e.entityType) m.set(e.entityType, (m.get(e.entityType) ?? 0) + 1);
        return m;
    }, [entries]);

    const actorTotal = useMemo(() => {
        const m = new Map<string, number>();
        for (const e of entries) {
            const k = actorKeyOf(e);
            m.set(k, (m.get(k) ?? 0) + 1);
        }
        return m;
    }, [entries, actorKeyOf]);

    const verbOptions = useMemo(
        () => [...verbTotal.entries()].sort((a, b) => b[1] - a[1]).map(([value, total]) => ({ value, total, label: verbLabel(value, t) })),
        [verbTotal, t],
    );

    const entityOptions = useMemo(
        () => [...entityTotal.entries()].sort((a, b) => b[1] - a[1]).map(([value, total]) => ({ value, total })),
        [entityTotal],
    );

    const actorOptions = useMemo(
        () => [...actorTotal.entries()].sort((a, b) => b[1] - a[1]).map(([value, total]) => ({ value, total })),
        [actorTotal],
    );

    const matches = useMemo(
        () =>
            function matchesFn(e: AuditLogEntry, except: FacetExcept): boolean {
                if (except !== "query") {
                    const q = filters.query.trim().toLowerCase();
                    if (q && !SEARCH_KEYS.some((k) => String(e[k] ?? "").toLowerCase().includes(q))) return false;
                }
                if (except !== "verbs") {
                    if (filters.verbs.size && !filters.verbs.has(verbOf(e.action))) return false;
                }
                if (except !== "entities") {
                    if (filters.entities.size && !(e.entityType != null && filters.entities.has(e.entityType))) return false;
                }
                if (except !== "outcome") {
                    if (filters.outcome !== "all") {
                        const failed = isFailed(e);
                        if (filters.outcome === "failed" && !failed) return false;
                        if (filters.outcome === "success" && failed) return false;
                    }
                }
                if (except !== "actors") {
                    if (filters.actors.size && !filters.actors.has(actorKeyOf(e))) return false;
                }
                if (except !== "range" && filters.range !== "all") {
                    const ms = parseMysqlDateTime(e.createdAt);
                    if (!Number.isNaN(ms) && ms < rangeCutoff(filters.range, now)) return false;
                }
                return true;
            },
        [filters, now, actorKeyOf],
    );

    const filtered = useMemo(
        () => entries.filter((e) => matches(e, null)),
        [entries, matches],
    );

    const verbCounts = useMemo(() => countBy(matches, entries, "verbs", (e) => verbOf(e.action)), [matches, entries]);
    const entityCounts = useMemo(() => countBy(matches, entries, "entities", (e) => e.entityType ?? ""), [matches, entries]);
    const actorCounts = useMemo(() => countBy(matches, entries, "actors", actorKeyOf), [matches, entries, actorKeyOf]);

    const outcomeCounts = useMemo(() => {
        let success = 0;
        let failed = 0;
        for (const e of entries) {
            if (!matches(e, "outcome")) continue;
            if (isFailed(e)) failed++;
            else success++;
        }
        return { success, failed, all: success + failed };
    }, [matches, entries]);

    const rangeCounts = useMemo(() => {
        if (now == null) return { all: 0, today: 0, "7d": 0, "30d": 0 };
        const base = now;
        const today = startOfLocalDay(base);
        const d7 = base - 7 * DAY_MS;
        const d30 = base - 30 * DAY_MS;
        const counts = { all: 0, today: 0, "7d": 0, "30d": 0 };
        for (const e of entries) {
            if (!matches(e, "range")) continue;
            counts.all++;
            const ms = parseMysqlDateTime(e.createdAt);
            if (Number.isNaN(ms)) continue;
            if (ms >= today) counts.today++;
            if (ms >= d7) counts["7d"]++;
            if (ms >= d30) counts["30d"]++;
        }
        return counts;
    }, [matches, entries, now]);

    const groups = useMemo(() => {
        const map = new Map<number, AuditLogEntry[]>();
        for (const e of filtered) {
            const ms = parseMysqlDateTime(e.createdAt);
            const key = Number.isNaN(ms) ? 0 : startOfLocalDay(ms);
            const bucket = map.get(key);
            if (bucket) bucket.push(e);
            else map.set(key, [e]);
        }
        const arr = [...map.entries()].map(([key, items]) => ({
            key,
            items: items.sort((x, y) => {
                const a = parseMysqlDateTime(x.createdAt) || 0;
                const b = parseMysqlDateTime(y.createdAt) || 0;
                return sort === "newest" ? b - a : a - b;
            }),
        }));
        arr.sort((a, b) => (sort === "newest" ? b.key - a.key : a.key - b.key));
        return arr;
    }, [filtered, sort]);

    const feed = useMemo<FeedEntry[]>(() => {
        const out: FeedEntry[] = [];
        for (const g of groups) {
            out.push({
                kind: "day",
                id: `day-${g.key}`,
                label: dayLabel(g.key, now, locale, t),
                count: g.items.length,
                connectUp: false,
                connectDown: false,
            });
            for (const item of g.items) {
                out.push({
                    kind: "row",
                    id: `row-${item.id}`,
                    entry: item,
                    isLastInGroup: false,
                    connectUp: false,
                    connectDown: false,
                });
            }
        }
        for (let i = 0; i < out.length; i++) {
            const e = out[i];
            e.connectUp = i > 0;
            e.connectDown = i < out.length - 1;
            if (e.kind === "row") {
                e.isLastInGroup = i === out.length - 1 || out[i + 1].kind === "day";
            }
        }
        return out;
    }, [groups, now, locale, t]);

    const stats = useMemo(() => {
        const failedCount = filtered.reduce((n, e) => (isFailed(e) ? n + 1 : n), 0);
        const actors = new Set<string>();
        let lastMs = -Infinity;
        for (const e of filtered) {
            actors.add(actorKeyOf(e));
            const ms = parseMysqlDateTime(e.createdAt);
            if (!Number.isNaN(ms) && ms > lastMs) lastMs = ms;
        }
        return { total: filtered.length, failed: failedCount, actors: actors.size, lastMs };
    }, [filtered, actorKeyOf]);

    const pulse = useMemo(() => {
        if (now == null) {
            return {
                days: Array.from({ length: PULSE_DAYS }, () => ({ key: 0, count: 0 })),
                max: 1,
                today: 0,
            };
        }
        const base = now;
        const today = startOfLocalDay(base);
        const days = Array.from({ length: PULSE_DAYS }, (_, i) => ({
            key: today - (PULSE_DAYS - 1 - i) * DAY_MS,
            count: 0,
        }));
        const idx = new Map(days.map((d, i) => [d.key, i]));
        for (const e of entries) {
            const ms = parseMysqlDateTime(e.createdAt);
            if (Number.isNaN(ms)) continue;
            const i = idx.get(startOfLocalDay(ms));
            if (i != null) days[i].count++;
        }
        const max = Math.max(1, ...days.map((d) => d.count));
        return { days, max, today };
    }, [entries, now]);

    const hasActiveFilters =
        filters.query.trim() !== "" ||
        filters.verbs.size > 0 ||
        filters.entities.size > 0 ||
        filters.outcome !== "all" ||
        filters.actors.size > 0 ||
        filters.range !== "all";

    function toggleSet(facet: "verbs" | "entities" | "actors", value: string) {
        setFilters((prev) => {
            const next = new Set(prev[facet]);
            if (next.has(value)) next.delete(value);
            else next.add(value);
            return { ...prev, [facet]: next };
        });
    }

    function clearFacet(facet: "verbs" | "entities" | "actors") {
        setFilters((prev) => ({ ...prev, [facet]: new Set() }));
    }

    function clearAll() {
        setFilters({
            query: "",
            verbs: new Set(),
            entities: new Set(),
            outcome: "all",
            actors: new Set(),
            range: "all",
        });
    }

    function toggleExpand(id: number) {
        setExpanded((prev) => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id);
            else next.add(id);
            return next;
        });
    }

    const activeChips: FilterChipData[] = (() => {
        const chips: FilterChipData[] = [];
        const q = filters.query.trim();
        if (q) chips.push({ id: "q", label: t("chipSearch", { query: q }), onRemove: () => setFilters((p) => ({ ...p, query: "" })) });
        for (const v of filters.verbs) {
            const opt = verbOptions.find((o) => o.value === v);
            chips.push({ id: `v-${v}`, label: opt ? opt.label : v, onRemove: () => toggleSet("verbs", v) });
        }
        for (const e of filters.entities) {
            chips.push({ id: `e-${e}`, label: e, onRemove: () => toggleSet("entities", e) });
        }
        if (filters.outcome !== "all") {
            const label = filters.outcome === "failed" ? t("outcomeFailed") : t("outcomeSuccess");
            chips.push({ id: "o", label, onRemove: () => setFilters((p) => ({ ...p, outcome: "all" })) });
        }
        if (filters.range !== "all") {
            const map: Record<Exclude<RangeFilter, "all">, string> = {
                today: t("dateToday"),
                "7d": t("date7d"),
                "30d": t("date30d"),
            };
            chips.push({ id: "r", label: map[filters.range], onRemove: () => setFilters((p) => ({ ...p, range: "all" })) });
        }
        for (const a of filters.actors) {
            chips.push({ id: `a-${a}`, label: a, onRemove: () => toggleSet("actors", a) });
        }
        return chips;
    })();

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-10">
                <Rise delay={0}>
                    <header className="flex flex-wrap items-end justify-between gap-4">
                        <div>
                            <h1 className="text-3xl font-bold tracking-tight text-balance text-foreground sm:text-4xl">{t("heading")}</h1>
                            <p className="mt-1.5 text-sm text-muted-foreground">{t("subtitle")}</p>
                        </div>
                        {entries.length > 0 && (
                            <StatCluster
                                stats={stats}
                                lastMs={stats.lastMs === -Infinity ? null : stats.lastMs}
                                now={now}
                                locale={locale}
                                t={t}
                            />
                        )}
                    </header>
                </Rise>

                {entries.length > 0 && (
                    <Rise delay={0.06}>
                        <PulseStrip pulse={pulse} reduce={reduce} t={t} locale={locale} />
                    </Rise>
                )}

                {entries.length === 0 ? (
                    <EmptyState title={t("emptyAllTitle")} body={t("emptyAllBody")} />
                ) : (
                    <Rise delay={0.12} className="flex flex-col gap-6">
                    <FilterBar
                        reduce={reduce}
                        chips={activeChips}
                        hasActiveFilters={hasActiveFilters}
                        onClearAll={clearAll}
                        clearAllLabel={t("clearAll")}
                        search={
                            <SearchField
                                value={filters.query}
                                onChange={(v) => setFilters((p) => ({ ...p, query: v }))}
                                onClear={() => setFilters((p) => ({ ...p, query: "" }))}
                                placeholder={t("searchPlaceholder")}
                                searchAria={t("searchAria")}
                                clearAria={t("clearSearchAria")}
                            />
                        }
                    >
                        <MultiSelectFilter
                            label={t("filterAction")}
                            ariaLabel={t("filterAction")}
                            options={verbOptions}
                            counts={verbCounts}
                            selected={filters.verbs}
                            onToggle={(v) => toggleSet("verbs", v)}
                            onClear={() => clearFacet("verbs")}
                            clearLabel={t("clear")}
                        />
                        <MultiSelectFilter
                            label={t("filterEntity")}
                            ariaLabel={t("filterEntity")}
                            options={entityOptions}
                            counts={entityCounts}
                            selected={filters.entities}
                            onToggle={(v) => toggleSet("entities", v)}
                            onClear={() => clearFacet("entities")}
                            clearLabel={t("clear")}
                            capitalize
                        />
                        <RadioFilter
                            label={t("filterOutcome")}
                            ariaLabel={t("filterOutcome")}
                            value={filters.outcome}
                            onValueChange={(v) => setFilters((p) => ({ ...p, outcome: v as OutcomeFilter }))}
                            options={[
                                { value: "all", label: t("outcomeAll"), count: outcomeCounts.all },
                                { value: "success", label: t("outcomeSuccess"), count: outcomeCounts.success },
                                { value: "failed", label: t("outcomeFailed"), count: outcomeCounts.failed },
                            ]}
                        />
                        <RadioFilter
                            label={t("filterDate")}
                            ariaLabel={t("filterDate")}
                            value={filters.range}
                            onValueChange={(v) => setFilters((p) => ({ ...p, range: v as RangeFilter }))}
                            options={[
                                { value: "all", label: t("dateAll"), count: rangeCounts.all },
                                { value: "today", label: t("dateToday"), count: rangeCounts.today },
                                { value: "7d", label: t("date7d"), count: rangeCounts["7d"] },
                                { value: "30d", label: t("date30d"), count: rangeCounts["30d"] },
                            ]}
                        />
                        <MultiSelectFilter
                            label={t("filterActor")}
                            ariaLabel={t("filterActor")}
                            options={actorOptions}
                            counts={actorCounts}
                            selected={filters.actors}
                            onToggle={(v) => toggleSet("actors", v)}
                            onClear={() => clearFacet("actors")}
                            clearLabel={t("clear")}
                            scroll
                        />
                    </FilterBar>

                    <div className="flex items-center justify-between gap-3 px-1">
                        <span className="text-xs tabular-nums text-muted-foreground">
                            {t("resultCount", { shown: filtered.length, total: entries.length })}
                        </span>
                        <SortToggle
                            value={sort}
                            onChange={setSort}
                            options={[
                                { value: "newest", label: t("sortNewest"), icon: <ArrowDownIcon className="size-3.5" /> },
                                { value: "oldest", label: t("sortOldest"), icon: <ArrowUpIcon className="size-3.5" /> },
                            ]}
                        />
                    </div>

                    {filtered.length === 0 ? (
                        hasActiveFilters && hasMore ? (
                            <EmptyState
                                title={t("noMatchesTitle")}
                                body={t("noMatchesMoreBody", { loaded: entries.length })}
                                muted
                            />
                        ) : (
                            <EmptyState
                                title={t("noMatchesTitle")}
                                body={t("noMatchesBody")}
                                muted
                                actionLabel={t("clearAll")}
                                onAction={clearAll}
                            />
                        )
                    ) : (
                        <ul className="relative">
                            <AnimatePresence initial={false} mode="popLayout">
                                {feed.map((entry) =>
                                    entry.kind === "day" ? (
                                        <DayMarker
                                            key={entry.id}
                                            label={entry.label}
                                            count={entry.count}
                                            connectUp={entry.connectUp}
                                            connectDown={entry.connectDown}
                                            reduce={reduce}
                                        />
                                    ) : (
                                        <AuditRow
                                            key={entry.id}
                                            entry={entry.entry}
                                            isLastInGroup={entry.isLastInGroup}
                                            connectUp={entry.connectUp}
                                            connectDown={entry.connectDown}
                                            expanded={expanded.has(entry.entry.id)}
                                            onToggle={() => toggleExpand(entry.entry.id)}
                                            now={now}
                                            locale={locale}
                                            t={t}
                                            reduce={reduce}
                                        />
                                    ),
                                )}
                            </AnimatePresence>
                        </ul>
                    )}

                    {hasMore && (
                        <LoadMore
                            loading={loadingMore}
                            error={loadError}
                            onLoad={loadMore}
                            t={t}
                        />
                    )}
                    </Rise>
                )}
            </div>
        </div>
    );
}

function LoadMore({
    loading,
    error,
    onLoad,
    t,
}: {
    loading: boolean;
    error: boolean;
    onLoad: () => void;
    t: Translator;
}) {
    const label = loading ? t("loadMoreLoading") : error ? t("loadMoreRetry") : t("loadMore");
    return (
        <div className="flex flex-col items-center gap-2 pt-2">
            <p role="status" aria-live="polite" className="sr-only">
                {loading ? t("loadMoreLoading") : ""}
            </p>
            {error && (
                <p role="alert" className="text-xs text-destructive">
                    {t("loadMoreError")}
                </p>
            )}
            <button
                type="button"
                onClick={onLoad}
                disabled={loading}
                aria-busy={loading}
                className="inline-flex h-9 items-center gap-2 rounded-full bg-muted px-4 text-sm font-medium text-foreground ring-1 ring-border outline-none transition duration-150 ease-out hover:bg-accent active:scale-[0.97] focus-visible:ring-2 focus-visible:ring-brand/40 disabled:cursor-not-allowed disabled:opacity-70 disabled:active:scale-100"
            >
                {loading || error ? (
                    <ArrowPathIcon
                        className={cn("size-4 text-muted-foreground", loading && "motion-safe:animate-spin [animation-duration:0.6s]")}
                    />
                ) : (
                    <ArrowDownIcon className="size-4 text-muted-foreground" />
                )}
                {label}
            </button>
        </div>
    );
}

function countBy(
    matches: (e: AuditLogEntry, except: FacetExcept) => boolean,
    entries: AuditLogEntry[],
    except: FacetExcept,
    keyOf: (e: AuditLogEntry) => string,
): Map<string, number> {
    const m = new Map<string, number>();
    for (const e of entries) {
        if (!matches(e, except)) continue;
        const k = keyOf(e);
        if (!k) continue;
        m.set(k, (m.get(k) ?? 0) + 1);
    }
    return m;
}

function verbLabel(verb: string, t: ReturnType<typeof useTranslations>): string {
    return VERB_META[verb] ? t(VERB_META[verb].verbKey) : verb;
}

function dayLabel(
    key: number,
    now: number | null,
    locale: string,
    t: ReturnType<typeof useTranslations>,
): string {
    if (key === 0) return "—";
    if (now != null) {
        const today = startOfLocalDay(now);
        if (key === today) return t("groupToday");
        if (key === today - DAY_MS) return t("groupYesterday");
    }
    const d = new Date(key);
    const currentYear = new Date(now ?? key).getFullYear();
    return new Intl.DateTimeFormat(locale, {
        weekday: "long",
        month: "long",
        day: "numeric",
        year: d.getFullYear() === currentYear ? undefined : "numeric",
    }).format(d);
}

function StatCluster({
    stats,
    lastMs,
    now,
    locale,
    t,
}: {
    stats: { total: number; failed: number; actors: number };
    lastMs: number | null;
    now: number | null;
    locale: string;
    t: ReturnType<typeof useTranslations>;
}) {
    const last = lastMs == null || now == null ? t("lastEventNever") : formatRelativeTime(new Date(lastMs).toISOString(), locale, now);
    return (
        <div className="flex items-center gap-4 text-sm sm:gap-5">
            <Stat value={stats.total} label={t("statEvents")} />
            <Divider />
            <Stat value={stats.failed} label={t("statFailed")} danger={stats.failed > 0} />
            <Divider />
            <Stat value={stats.actors} label={t("statActors")} />
            <Divider className="hidden sm:block" />
            <div className="hidden sm:block">
                <div className="text-sm font-semibold tabular-nums text-foreground">{last}</div>
                <div className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">{t("statLastEvent")}</div>
            </div>
        </div>
    );
}

function Divider({ className }: { className?: string }) {
    return <span aria-hidden className={cn("h-8 w-px shrink-0 bg-border/50", className)} />;
}

function Stat({ value, label, danger }: { value: number; label: string; danger?: boolean }) {
    return (
        <div>
            <div className={cn("text-sm font-semibold tabular-nums", danger ? "text-destructive" : "text-foreground")}>
                {value}
            </div>
            <div className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">{label}</div>
        </div>
    );
}

function PulseStrip({
    pulse,
    reduce,
    t,
    locale,
}: {
    pulse: { days: { key: number; count: number }[]; max: number; today: number };
    reduce: boolean;
    t: ReturnType<typeof useTranslations>;
    locale: string;
}) {
    const fmt = useMemo(
        () => new Intl.DateTimeFormat(locale, { month: "short", day: "numeric" }),
        [locale],
    );
    return (
        <div className="flex items-center gap-5 rounded-2xl border border-border bg-card p-4">
            <div className="shrink-0">
                <div className="text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">{t("pulseLabel")}</div>
                <div className="mt-0.5 text-2xl font-semibold tabular-nums text-foreground">
                    {pulse.days.reduce((n, d) => n + d.count, 0)}
                </div>
            </div>
            <div
                className="ml-auto flex h-12 items-end gap-1.5"
                role="img"
                aria-label={t("pulseLabel")}
            >
                {pulse.days.map((day, i) => {
                    const isToday = day.key === pulse.today;
                    const height = Math.max(0.08, day.count / pulse.max) * 48;
                    const title = day.key === 0 ? undefined : `${fmt.format(new Date(day.key))} — ${day.count}`;
                    return (
                        <motion.span
                            key={day.key === 0 ? `pulse-${i}` : day.key}
                            className={cn(
                                "block w-2 rounded-full transition-colors",
                                isToday ? "bg-brand" : "bg-brand/30 hover:bg-brand/55",
                            )}
                            style={{ height, transformOrigin: "bottom" }}
                            title={title}
                            initial={reduce ? false : { scaleY: 0 }}
                            animate={{ scaleY: 1 }}
                            transition={{ duration: reduce ? 0 : 0.5, delay: reduce ? 0 : i * 0.03, ease: EASE_OUT }}
                        />
                    );
                })}
            </div>
        </div>
    );
}

type Translator = ReturnType<typeof useTranslations>;

function DayMarker({
    label,
    count,
    connectUp,
    connectDown,
    reduce,
}: {
    label: string;
    count: number;
    connectUp: boolean;
    connectDown: boolean;
    reduce: boolean;
}) {
    return (
        <motion.li
            layout={!reduce}
            initial={false}
            transition={{ duration: 0.22, ease: EASE_OUT }}
            className="grid grid-cols-[2rem_minmax(0,1fr)] gap-3"
        >
            <div className="relative flex flex-col items-center">
                <span
                    aria-hidden
                    className="absolute left-1/2 w-px -translate-x-1/2 bg-border"
                    style={{
                        top: connectUp ? 0 : DATE_ANCHOR,
                        bottom: connectDown ? 0 : `calc(100% - ${DATE_ANCHOR}px)`,
                    }}
                />
                <span aria-hidden className="relative z-10 mt-3.5 size-2 rounded-full bg-muted-foreground ring-4 ring-background" />
            </div>
            <div className="flex items-baseline gap-2 pt-2 pb-1.5">
                <h3 className="text-sm font-semibold text-foreground">{label}</h3>
                <span className="text-xs tabular-nums text-muted-foreground">{count}</span>
            </div>
        </motion.li>
    );
}

function AuditRow({
    entry,
    isLastInGroup,
    connectUp,
    connectDown,
    expanded,
    onToggle,
    now,
    locale,
    t,
    reduce,
}: {
    entry: AuditLogEntry;
    isLastInGroup: boolean;
    connectUp: boolean;
    connectDown: boolean;
    expanded: boolean;
    onToggle: () => void;
    now: number | null;
    locale: string;
    t: Translator;
    reduce: boolean;
}) {
    const verb = verbOf(entry.action);
    const meta = VERB_META[verb];
    const failure = isFailed(entry);
    const tone: Tone = failure ? "delete" : meta?.tone ?? "default";
    const Icon = meta?.icon ?? BoltIcon;

    const ms = parseMysqlDateTime(entry.createdAt);
    const hasMs = !Number.isNaN(ms);
    const absolute = hasMs
        ? new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "medium" }).format(ms)
        : entry.createdAt;
    const timeLabel = now == null ? formatShortDate(entry.createdAt, locale) : formatRelativeTime(entry.createdAt, locale, now);

    const actorName = entry.actorLabel ?? t("actorSystem");
    const verbText = meta ? t(meta.verbKey) : verb;
    const actorTag = (chunks: React.ReactNode) => <span className="font-semibold text-foreground">{chunks}</span>;
    const targetTag = (chunks: React.ReactNode) => <span className="font-medium text-foreground">{chunks}</span>;
    const actionLine = entry.targetLabel && !PLAIN_VERBS.has(verb)
        ? t.rich("actionEntity", {
              actorName,
              targetName: entry.targetLabel,
              verb: verbText,
              actor: actorTag,
              target: targetTag,
          })
        : t.rich("actionPlain", { actorName, verb: verbText, actor: actorTag });

    const changeEntries: [string, AuditChange][] = entry.changes ? Object.entries(entry.changes) : [];
    const errorText = typeof entry.context?.error === "string" ? entry.context.error : null;
    const detailRows = [
        entry.ipAddress && { label: t("metaIp"), value: entry.ipAddress, mono: true },
        entry.userAgent && { label: t("metaUserAgent"), value: entry.userAgent, mono: false },
        entry.requestId && { label: t("metaRequestId"), value: entry.requestId, mono: true },
    ].filter(Boolean) as { label: string; value: string; mono: boolean }[];

    return (
        <motion.li
            layout={!reduce}
            initial={false}
            exit={reduce ? { opacity: 0 } : { opacity: 0, x: 8, transition: { duration: 0.2, ease: EASE_OUT } }}
            transition={{ duration: 0.22, ease: EASE_OUT }}
            className="grid grid-cols-[2rem_minmax(0,1fr)] gap-3"
        >
            <div className="relative flex flex-col items-center">
                <span
                    aria-hidden
                    className="absolute left-1/2 w-px -translate-x-1/2 bg-border"
                    style={{
                        top: connectUp ? 0 : NODE_ANCHOR,
                        bottom: connectDown ? 0 : `calc(100% - ${NODE_ANCHOR}px)`,
                    }}
                />
                <span
                    className={cn(
                        "relative z-10 mt-0.5 grid size-8 place-items-center rounded-full ring-4 ring-background transition-transform duration-150 group-active:scale-90",
                        TONE_DOT[tone],
                    )}
                >
                    <Icon className="size-4" />
                </span>
            </div>

            <div className="min-w-0 pb-4">
                <button
                    type="button"
                    onClick={onToggle}
                    aria-expanded={expanded}
                    className="group block w-full rounded-xl px-3 py-2 text-left outline-none transition-colors hover:bg-muted focus-visible:ring-2 focus-visible:ring-brand/40"
                >
                    <div className="flex items-start gap-3">
                        <div className="min-w-0 flex-1">
                            <p className="text-sm leading-snug text-foreground/75">{actionLine}</p>
                            {entry.summary && (
                                <p className="mt-0.5 truncate text-xs text-muted-foreground">{entry.summary}</p>
                            )}
                        </div>
                        <div className="flex shrink-0 items-center gap-2 pt-0.5">
                            {failure && (
                                <Badge variant="destructive" className="hidden sm:inline-flex">
                                    {t("outcomeFailed")}
                                </Badge>
                            )}
                            <time
                                dateTime={hasMs ? new Date(ms).toISOString() : undefined}
                                title={absolute}
                                className="text-xs tabular-nums text-muted-foreground"
                            >
                                {timeLabel}
                            </time>
                            <ChevronDownIcon
                                className={cn(
                                    "size-4 text-muted-foreground/60 transition-[transform,color] duration-200 ease-out group-hover:text-muted-foreground",
                                    expanded && "rotate-180 text-muted-foreground",
                                )}
                            />
                        </div>
                    </div>
                </button>

                <div
                    className="grid px-3 transition-[grid-template-rows] duration-200 ease-[cubic-bezier(0.23,1,0.32,1)]"
                    style={{ gridTemplateRows: expanded ? "1fr" : "0fr" }}
                >
                    <div className="overflow-hidden">
                        <div className="my-1.5 rounded-xl border border-border/60 bg-muted/30 p-3.5">
                            {errorText && (
                                <div className="mb-3 flex items-start gap-2 rounded-lg bg-destructive/10 px-3 py-2 text-xs text-destructive">
                                    <span className="font-medium uppercase tracking-wide">{t("metaError")}</span>
                                    <span className="min-w-0 break-words font-mono">{errorText}</span>
                                </div>
                            )}
                            {changeEntries.length > 0 ? (
                                <dl className="space-y-2">
                                    {changeEntries.map(([field, delta]) => (
                                        <div
                                            key={field}
                                            className="flex flex-wrap items-baseline gap-x-2.5 gap-y-1"
                                        >
                                            <dt className="font-mono text-[11px] text-muted-foreground">{field}</dt>
                                            <dd className="flex min-w-0 items-center gap-1.5">
                                                <ValueChip value={delta.old} tone="old" empty={t("emptyValue")} />
                                                <ArrowRightIcon className="size-3 shrink-0 text-muted-foreground/50" />
                                                <ValueChip value={delta.new} tone="new" empty={t("emptyValue")} />
                                            </dd>
                                        </div>
                                    ))}
                                </dl>
                            ) : (
                                !errorText && <p className="text-xs text-muted-foreground">{t("noChanges")}</p>
                            )}

                            {detailRows.length > 0 && (
                                <dl className="mt-3 grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1.5 border-t border-border/60 pt-3">
                                    {detailRows.map((row) => (
                                        <div key={row.label} className="contents">
                                            <dt className="text-[11px] uppercase tracking-wide text-muted-foreground/80">
                                                {row.label}
                                            </dt>
                                            <dd
                                                className={cn(
                                                    "min-w-0 truncate text-xs text-foreground/90",
                                                    row.mono && "font-mono",
                                                )}
                                                title={row.value}
                                            >
                                                {row.value}
                                            </dd>
                                        </div>
                                    ))}
                                </dl>
                            )}
                        </div>
                    </div>
                </div>
                {isLastInGroup && <div className="h-2" />}
            </div>
        </motion.li>
    );
}

function ValueChip({ value, tone, empty }: { value: unknown; tone: "old" | "new"; empty: string }) {
    const isEmpty = value === null || value === undefined || value === "";
    const text = isEmpty
        ? empty
        : typeof value === "object"
          ? JSON.stringify(value)
          : String(value);
    return (
        <span
            className={cn(
                "max-w-[16rem] truncate rounded-md px-1.5 py-0.5 font-mono text-[11px]",
                isEmpty && "bg-muted italic text-muted-foreground/70",
                !isEmpty && tone === "old" && "bg-muted text-muted-foreground",
                !isEmpty && tone === "new" && "bg-chart-2/10 text-chart-2",
            )}
            title={text}
        >
            {text}
        </span>
    );
}

function EmptyState({
    title,
    body,
    muted,
    actionLabel,
    onAction,
}: {
    title: string;
    body: string;
    muted?: boolean;
    actionLabel?: string;
    onAction?: () => void;
}) {
    return (
        <div className="flex flex-col items-center justify-center rounded-2xl border border-dashed border-border/70 bg-card/40 py-20 text-center">
            <span
                className={cn(
                    "grid size-12 place-items-center rounded-full",
                    muted ? "bg-muted text-muted-foreground" : "bg-brand-light text-brand-dark",
                )}
            >
                <ClipboardDocumentListIcon className="size-6" />
            </span>
            <p className="mt-4 text-sm font-medium text-foreground">{title}</p>
            <p className="mt-1 max-w-xs text-sm text-muted-foreground">{body}</p>
            {actionLabel && onAction && (
                <button
                    type="button"
                    onClick={onAction}
                    className="mt-4 inline-flex h-8 items-center gap-1.5 rounded-full bg-muted px-3.5 text-xs font-medium text-foreground ring-1 ring-border outline-none transition hover:bg-accent active:scale-[0.97] focus-visible:ring-2 focus-visible:ring-brand/40"
                >
                    <FunnelIcon className="size-3.5" />
                    {actionLabel}
                </button>
            )}
        </div>
    );
}