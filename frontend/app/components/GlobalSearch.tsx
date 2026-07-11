'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { Command as CommandPrimitive } from 'cmdk';
import { MagnifyingGlassIcon } from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import { CommandGroup, CommandItem, CommandList, CommandSeparator, CommandShortcut } from '@/components/ui/command';
import { useActions, useAvailableActions } from '@/app/hooks/useActions';
import { ACTION_GROUPS, type ActionGroup, type AppAction } from '@/app/lib/actions/types';
import { search as searchApi } from '@/app/lib/api';
import type { SearchResults } from '@/app/lib/types';
import { buildSearchGroups, openResult, type ResultGroup } from '@/app/lib/search/resultGroups';
import { cn } from '@/lib/utils';

const MIN_QUERY_LENGTH = 2;
const DEBOUNCE_MS = 250;

/** The registry groups shown, in order, when the palette opens with an empty query. */
const EMPTY_GROUP_ORDER: readonly ActionGroup[] = ['record', 'create', 'navigate', 'workspace'];

const SHORTCUT_GLYPHS: Record<string, string> = { mod: '⌘', ctrl: '⌃', alt: '⌥', shift: '⇧' };

/** The pill morph (header ↔ centered) — position-only, so the bar keeps its shape. */
const PILL_MORPH = { type: 'spring', stiffness: 440, damping: 38, mass: 0.9 } as const;
/** The panel's slide-down entrance from beneath the pill. */
const PANEL_SLIDE = { type: 'spring', stiffness: 360, damping: 30, mass: 0.8 } as const;
/** The bouncy hover extend/collapse of the panel window. */
const PANEL_BOUNCE = { type: 'spring', stiffness: 300, damping: 15, mass: 0.85 } as const;
/** Panel heights (px): a small window by default, a larger scrollable one on hover. */
const PANEL_COLLAPSED = 168;
const PANEL_EXPANDED = 452;

const PILL_SHELL =
    'relative flex w-full items-center rounded-full bg-muted ring-1 ring-border focus-within:ring-2 focus-within:ring-brand';
const PILL_INPUT =
    'w-full rounded-full bg-transparent py-2.5 pr-16 pl-11 text-base text-foreground placeholder:text-muted-foreground outline-none';

type Mode = 'inline' | 'palette';
type ScopedResults = { query: string; data: SearchResults };

/** Renders a normalized chord as compact glyphs for display alongside a command. */
function formatShortcut(chord: string): string {
    return chord
        .split('+')
        .map((part) => SHORTCUT_GLYPHS[part] ?? part.toUpperCase())
        .join('');
}

/** The lowercased haystack a command is matched against: its label plus locale-neutral and localized aliases. */
function actionSearchText(action: AppAction, t: (key: string) => string): string {
    const parts = [t(action.labelKey)];
    if (action.keywords) parts.push(...action.keywords);
    if (action.keywordsKey) parts.push(t(action.keywordsKey));
    return parts.join(' ').toLowerCase();
}

/** Ranks a command so label prefix matches sort above mere substring/alias matches. */
function rankAction(action: AppAction, lowerQuery: string, t: (key: string) => string): number {
    return t(action.labelKey).toLowerCase().startsWith(lowerQuery) ? 0 : 1;
}

const EMPTY_RESULTS: SearchResults = {
    users: [],
    companies: [],
    people: [],
    deals: [],
    pipelines: [],
    tags: [],
    activities: [],
    notes: [],
    tasks: [],
    attachments: [],
};

/**
 * The unified global search surface. As an inline field in the app header it runs the debounced
 * record-search dropdown; pressing `Cmd/Ctrl+K` from anywhere slides the same pill to the centre of the
 * viewport (keeping its shape) and slides a command panel down from beneath it — permission-aware
 * registry commands plus record search, carrying the query across. The panel shows a small window that
 * springs open to a larger scrollable one on hover. The centred field stays anchored and focused so it
 * never moves as the panel resizes. Escape, outside-click, or selecting a result returns to the inline
 * field; reduced-motion drops the spring/slide.
 */
export default function GlobalSearch() {
    const tSearch = useTranslations('CommonSearchBar');
    const tActions = useTranslations('Actions');
    const router = useRouter();
    const searchParams = useSearchParams();
    const urlQuery = searchParams.get('query') ?? '';
    const reduceMotion = useReducedMotion() ?? false;

    const { run, pendingIds } = useActions();
    const available = useAvailableActions();

    const [mode, setMode] = useState<Mode>('inline');
    const [query, setQuery] = useState(urlQuery);
    const [inlineOpen, setInlineOpen] = useState(false);
    const [expanded, setExpanded] = useState(false);
    const [results, setResults] = useState<ScopedResults | null>(null);
    const [activeIndex, setActiveIndex] = useState(-1);

    const containerRef = useRef<HTMLDivElement>(null);
    const inlineInputRef = useRef<HTMLInputElement>(null);
    const paletteInputRef = useRef<HTMLInputElement>(null);
    const paletteRef = useRef<HTMLDivElement>(null);
    const listRef = useRef<HTMLDivElement>(null);
    const lastFocusedRef = useRef<HTMLElement | null>(null);
    const modeRef = useRef<Mode>(mode);
    useEffect(() => {
        modeRef.current = mode;
    }, [mode]);

    const trimmed = query.trim();
    const lowerQuery = trimmed.toLowerCase();
    const isPalette = mode === 'palette';
    const shouldSearch = (inlineOpen || isPalette) && trimmed.length >= MIN_QUERY_LENGTH;
    const showInlineDropdown = mode === 'inline' && inlineOpen && trimmed.length >= MIN_QUERY_LENGTH;

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect
        if (urlQuery) setQuery(urlQuery);
    }, [urlQuery]);

    useEffect(() => {
        if (!shouldSearch) return;
        const controller = new AbortController();
        const timer = setTimeout(() => {
            searchApi(trimmed, { signal: controller.signal })
                .then((data) => setResults({ query: trimmed, data }))
                .catch(() => {
                    if (!controller.signal.aborted) setResults({ query: trimmed, data: EMPTY_RESULTS });
                });
        }, DEBOUNCE_MS);
        return () => {
            controller.abort();
            clearTimeout(timer);
        };
    }, [trimmed, shouldSearch]);

    const paletteData = results?.query === trimmed ? results.data : null;
    const paletteRecordGroups = useMemo<ResultGroup[]>(
        () => (trimmed.length >= MIN_QUERY_LENGTH ? buildSearchGroups(paletteData, tSearch) : []),
        [paletteData, trimmed, tSearch],
    );
    const inlineGroups = useMemo<ResultGroup[]>(
        () => (trimmed.length >= MIN_QUERY_LENGTH ? buildSearchGroups(results?.data ?? null, tSearch) : []),
        [results, trimmed, tSearch],
    );
    const flatRows = useMemo(() => inlineGroups.flatMap((group) => group.rows), [inlineGroups]);
    const searching = shouldSearch && results?.query !== trimmed;

    const commandGroups = useMemo(() => {
        const groupsToScan = lowerQuery ? ACTION_GROUPS : EMPTY_GROUP_ORDER;
        return groupsToScan
            .map((group) => {
                const actions = available.filter(
                    (action) => action.group === group && (!lowerQuery || actionSearchText(action, tActions).includes(lowerQuery)),
                );
                if (lowerQuery) actions.sort((a, b) => rankAction(a, lowerQuery, tActions) - rankAction(b, lowerQuery, tActions));
                return { group, actions };
            })
            .filter((entry) => entry.actions.length > 0);
    }, [available, lowerQuery, tActions]);

    const closePalette = useCallback(() => {
        setMode('inline');
        setExpanded(false);
        const opener = lastFocusedRef.current;
        lastFocusedRef.current = null;
        requestAnimationFrame(() => {
            if (opener && opener.isConnected) opener.focus();
        });
    }, []);

    useEffect(() => {
        const onKeyDown = (event: KeyboardEvent) => {
            if (event.repeat) return;
            if ((event.metaKey || event.ctrlKey) && !event.altKey && (event.key === 'k' || event.key === 'K')) {
                event.preventDefault();
                if (modeRef.current === 'palette') {
                    closePalette();
                } else {
                    const active = document.activeElement;
                    lastFocusedRef.current = active instanceof HTMLElement ? active : null;
                    setMode('palette');
                }
                return;
            }
            if (event.key === 'Escape' && modeRef.current === 'palette') {
                event.preventDefault();
                closePalette();
            }
        };
        document.addEventListener('keydown', onKeyDown);
        return () => document.removeEventListener('keydown', onKeyDown);
    }, [closePalette]);

    useEffect(() => {
        if (mode !== 'palette') return;
        const raf = requestAnimationFrame(() => paletteInputRef.current?.focus());
        return () => cancelAnimationFrame(raf);
    }, [mode]);

    useEffect(() => {
        if (mode !== 'inline' || !inlineOpen) return;
        const onPointerDown = (event: MouseEvent) => {
            if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
                setInlineOpen(false);
            }
        };
        document.addEventListener('mousedown', onPointerDown);
        return () => document.removeEventListener('mousedown', onPointerDown);
    }, [mode, inlineOpen]);

    useEffect(() => {
        if (activeIndex < 0) return;
        const el = listRef.current?.querySelector(`[data-index="${activeIndex}"]`);
        el?.scrollIntoView({ block: 'nearest' });
    }, [activeIndex]);

    const navigateInline = (href: string, external = false) => {
        setInlineOpen(false);
        setActiveIndex(-1);
        openResult(router, href, external);
    };

    const goToSearchPage = () => {
        if (!trimmed) return;
        setInlineOpen(false);
        setActiveIndex(-1);
        router.push(`/search?query=${encodeURIComponent(trimmed)}`);
    };

    const runAction = (id: string) => {
        void run(id, { source: 'palette' });
        closePalette();
    };

    const goToRecord = (href: string, external?: boolean) => {
        openResult(router, href, external);
        closePalette();
    };

    const onInlineKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
        if (event.key === 'Escape') {
            setInlineOpen(false);
            return;
        }
        if (event.key === 'Enter') {
            event.preventDefault();
            const active = showInlineDropdown && flatRows.length > 0 && activeIndex >= 0 ? flatRows[activeIndex] : undefined;
            if (active) navigateInline(active.href, active.external);
            else goToSearchPage();
            return;
        }
        if (!showInlineDropdown || flatRows.length === 0) return;
        if (event.key === 'ArrowDown') {
            event.preventDefault();
            setActiveIndex((i) => Math.min(i + 1, flatRows.length - 1));
        } else if (event.key === 'ArrowUp') {
            event.preventDefault();
            setActiveIndex((i) => Math.max(i - 1, 0));
        }
    };

    const onPaletteKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp' || event.key === 'PageDown' || event.key === 'PageUp') {
            setExpanded(true);
            return;
        }
        if (event.key !== 'Tab' || !paletteRef.current) return;
        const focusables = paletteRef.current.querySelectorAll<HTMLElement>(
            'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])',
        );
        if (focusables.length === 0) return;
        const first = focusables[0];
        const last = focusables[focusables.length - 1];
        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    };

    const inlineRecordCount = flatRows.length;
    const paletteRecordCount = paletteRecordGroups.reduce((sum, group) => sum + group.rows.length, 0);
    const commandCount = commandGroups.reduce((sum, entry) => sum + entry.actions.length, 0);
    const showNoResults = trimmed.length > 0 && !searching && commandCount + paletteRecordCount === 0;

    return (
        <div ref={containerRef} className="relative w-full">
            {isPalette ? (
                <div aria-hidden className="h-11 w-full" />
            ) : (
                <>
                    <motion.form
                        layoutId="global-search-pill"
                        role="search"
                        onSubmit={(event) => {
                            event.preventDefault();
                            goToSearchPage();
                        }}
                        className={PILL_SHELL}
                    >
                        <MagnifyingGlassIcon className="pointer-events-none absolute left-3.5 top-1/2 size-5 -translate-y-1/2 text-muted-foreground" />
                        <input
                            ref={inlineInputRef}
                            type="text"
                            value={query}
                            onChange={(event) => {
                                setQuery(event.target.value);
                                setActiveIndex(-1);
                                setInlineOpen(true);
                            }}
                            onClick={() => setInlineOpen(true)}
                            onKeyDown={onInlineKeyDown}
                            placeholder={tSearch('placeholder')}
                            className={PILL_INPUT}
                            role="combobox"
                            aria-expanded={showInlineDropdown}
                            aria-controls="global-search-listbox"
                            aria-autocomplete="list"
                            autoComplete="off"
                        />
                        <kbd className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 select-none rounded border border-border bg-background px-1.5 py-0.5 font-mono text-[10px] font-medium text-muted-foreground">
                            ⌘K
                        </kbd>
                    </motion.form>

                    {showInlineDropdown ? (
                        <div
                            ref={listRef}
                            id="global-search-listbox"
                            role="listbox"
                            className="absolute inset-x-0 top-full z-40 mt-2 max-h-[70vh] overflow-y-auto rounded-2xl bg-popover p-2 text-popover-foreground shadow-lg ring-1 ring-border"
                        >
                            {searching && inlineRecordCount === 0 ? (
                                <div className="flex justify-center py-3">
                                    <Loader2Icon className="size-5 animate-spin text-muted-foreground" />
                                </div>
                            ) : null}
                            {!searching && inlineRecordCount === 0 ? (
                                <p className="px-3 py-2 text-sm text-muted-foreground">{tSearch('noResults', { query: trimmed })}</p>
                            ) : null}
                            {inlineGroups.map((group) => (
                                <div key={group.key} className="mb-1 last:mb-0">
                                    <p className="px-3 pb-1 pt-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{group.heading}</p>
                                    {group.rows.map((row) => {
                                        const Icon = row.icon;
                                        return (
                                            <button
                                                key={row.key}
                                                type="button"
                                                data-index={row.index}
                                                onClick={() => navigateInline(row.href, row.external)}
                                                onMouseEnter={() => setActiveIndex(row.index)}
                                                className={cn(
                                                    'flex w-full items-center gap-3 rounded-xl px-3 py-2 text-left transition',
                                                    activeIndex === row.index ? 'bg-muted' : 'hover:bg-muted',
                                                )}
                                                role="option"
                                                aria-selected={activeIndex === row.index}
                                            >
                                                <span className="flex size-8 shrink-0 items-center justify-center">
                                                    {row.leading ? (
                                                        row.leading
                                                    ) : row.accent ? (
                                                        <span className="size-4 rounded-full ring-1 ring-border" style={{ backgroundColor: row.accent }} />
                                                    ) : Icon ? (
                                                        <Icon className="size-5 text-muted-foreground" />
                                                    ) : null}
                                                </span>
                                                <span className="min-w-0 flex-1">
                                                    <span className="block truncate text-sm text-foreground">{row.label}</span>
                                                    {row.subtitle ? (
                                                        <span className="block truncate text-xs text-muted-foreground">{row.subtitle}</span>
                                                    ) : null}
                                                </span>
                                            </button>
                                        );
                                    })}
                                </div>
                            ))}
                        </div>
                    ) : null}
                </>
            )}

            <AnimatePresence>
                {isPalette ? (
                    <motion.button
                        key="global-search-scrim"
                        type="button"
                        aria-hidden
                        tabIndex={-1}
                        onClick={closePalette}
                        initial={{ opacity: 0 }}
                        animate={{ opacity: 1 }}
                        exit={{ opacity: 0 }}
                        transition={{ duration: 0.16, ease: [0.23, 1, 0.32, 1] }}
                        className="fixed inset-0 z-40 cursor-default bg-black/50 backdrop-blur-[1px]"
                    />
                ) : null}
            </AnimatePresence>

            <AnimatePresence>
                {isPalette ? (
                    <motion.div
                        key="global-search-overlay"
                        ref={paletteRef}
                        role="dialog"
                        aria-modal
                        aria-label={tActions('palette.trigger')}
                        onKeyDown={onPaletteKeyDown}
                        initial={{ opacity: 0 }}
                        animate={{ opacity: 1 }}
                        exit={{ opacity: 0 }}
                        transition={{ duration: 0.14 }}
                        className="fixed inset-x-0 top-[calc(50vh-1.5rem)] z-50 mx-auto flex w-[min(36rem,92vw)] flex-col gap-2"
                    >
                        <CommandPrimitive shouldFilter={false} loop className="contents">
                            <motion.div layoutId="global-search-pill" transition={reduceMotion ? { duration: 0 } : PILL_MORPH} className={cn(PILL_SHELL, 'z-10 shadow-lg')}>
                                <MagnifyingGlassIcon className="pointer-events-none absolute left-3.5 top-1/2 size-5 -translate-y-1/2 text-muted-foreground" />
                                <CommandPrimitive.Input
                                    ref={paletteInputRef}
                                    value={query}
                                    onValueChange={setQuery}
                                    placeholder={tActions('palette.placeholder')}
                                    autoFocus
                                    className={PILL_INPUT}
                                />
                                <kbd className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 select-none rounded border border-border bg-background px-1.5 py-0.5 font-mono text-[10px] font-medium text-muted-foreground">
                                    esc
                                </kbd>
                            </motion.div>

                            <motion.div
                                initial={reduceMotion ? { opacity: 0 } : { opacity: 0, y: -24 }}
                                animate={{ opacity: 1, y: 0 }}
                                exit={reduceMotion ? { opacity: 0 } : { opacity: 0, y: -18 }}
                                transition={reduceMotion ? { duration: 0.12 } : PANEL_SLIDE}
                                onHoverStart={() => setExpanded(true)}
                                onHoverEnd={() => setExpanded(false)}
                                onMouseDown={(event) => event.preventDefault()}
                                className="relative z-0 overflow-hidden rounded-2xl bg-popover text-popover-foreground shadow-2xl ring-1 ring-border"
                            >
                                <motion.div
                                    animate={{ maxHeight: expanded ? PANEL_EXPANDED : PANEL_COLLAPSED }}
                                    transition={reduceMotion ? { duration: 0 } : PANEL_BOUNCE}
                                    className="no-scrollbar overflow-y-auto"
                                    style={{ maxHeight: PANEL_COLLAPSED }}
                                >
                                    <CommandList className="max-h-none overflow-visible p-1">
                                        {commandGroups.map((entry) => (
                                            <CommandGroup key={entry.group} heading={tActions(`group.${entry.group}`)}>
                                                {entry.actions.map((action) => {
                                                    const Icon = action.icon;
                                                    const pending = pendingIds.has(action.id);
                                                    return (
                                                        <CommandItem key={action.id} value={action.id} disabled={pending} onSelect={() => runAction(action.id)}>
                                                            {pending ? (
                                                                <Loader2Icon className="size-4 animate-spin text-muted-foreground" />
                                                            ) : Icon ? (
                                                                <Icon className="size-4 text-muted-foreground" />
                                                            ) : null}
                                                            <span className="flex-1 truncate">{tActions(action.labelKey)}</span>
                                                            {action.shortcut ? <CommandShortcut>{formatShortcut(action.shortcut)}</CommandShortcut> : null}
                                                        </CommandItem>
                                                    );
                                                })}
                                            </CommandGroup>
                                        ))}

                                        {commandGroups.length > 0 && paletteRecordGroups.length > 0 ? <CommandSeparator /> : null}

                                        {paletteRecordGroups.map((group) => (
                                            <CommandGroup key={group.key} heading={group.heading}>
                                                {group.rows.map((row) => {
                                                    const RowIcon = row.icon;
                                                    return (
                                                        <CommandItem key={row.key} value={row.key} onSelect={() => goToRecord(row.href, row.external)}>
                                                            <span className="flex size-6 shrink-0 items-center justify-center">
                                                                {row.leading ? (
                                                                    row.leading
                                                                ) : row.accent ? (
                                                                    <span className="size-3.5 rounded-full ring-1 ring-border" style={{ backgroundColor: row.accent }} />
                                                                ) : RowIcon ? (
                                                                    <RowIcon className="size-4 text-muted-foreground" />
                                                                ) : null}
                                                            </span>
                                                            <span className="min-w-0 flex-1">
                                                                <span className="block truncate">{row.label}</span>
                                                                {row.subtitle ? (
                                                                    <span className="block truncate text-xs text-muted-foreground">{row.subtitle}</span>
                                                                ) : null}
                                                            </span>
                                                        </CommandItem>
                                                    );
                                                })}
                                            </CommandGroup>
                                        ))}

                                        {searching ? (
                                            <div className="flex items-center justify-center gap-2 py-6 text-sm text-muted-foreground">
                                                <Loader2Icon className="size-4 animate-spin" />
                                                {tActions('palette.loading')}
                                            </div>
                                        ) : null}

                                        {showNoResults ? (
                                            <div className="py-8 text-center text-sm text-muted-foreground">{tActions('palette.noResults', { query: trimmed })}</div>
                                        ) : null}
                                    </CommandList>
                                </motion.div>
                            </motion.div>
                        </CommandPrimitive>
                    </motion.div>
                ) : null}
            </AnimatePresence>
        </div>
    );
}
