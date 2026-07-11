'use client';

import { useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';

import {
    CommandDialog,
    CommandGroup,
    CommandInput,
    CommandItem,
    CommandList,
    CommandSeparator,
    CommandShortcut,
} from '@/components/ui/command';
import { useActions, useAvailableActions } from '@/app/hooks/useActions';
import { ACTION_GROUPS, type ActionGroup, type AppAction } from '@/app/lib/actions/types';
import { search as searchApi } from '@/app/lib/api';
import type { SearchResults } from '@/app/lib/types';
import { buildSearchGroups, openResult } from '@/app/lib/search/resultGroups';

const MIN_QUERY_LENGTH = 2;
const DEBOUNCE_MS = 200;

/** The registry groups shown, in order, when the palette opens with an empty query. */
const EMPTY_GROUP_ORDER: readonly ActionGroup[] = ['record', 'create', 'navigate', 'workspace'];

const SHORTCUT_GLYPHS: Record<string, string> = { mod: '⌘', ctrl: '⌃', alt: '⌥', shift: '⇧' };

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

type ScopedResults = { query: string; data: SearchResults };

/**
 * The command-and-search palette. It renders registry commands (create, navigation, current-record,
 * workspace/preferences) and global record-search results in one accessible cmdk surface, driven with
 * custom filtering (`shouldFilter={false}`) so commands are matched by label + aliases and records come
 * from the shared search model. Empty query shows a structured set of common and current-context
 * commands; a typed query adds matching commands and debounced record results. Selecting a command runs
 * it through the shared registry; selecting a record navigates. Only permission-available actions appear.
 */
export default function CommandPalette({
    open,
    onOpenChange,
    query,
    onQueryChange,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    query: string;
    onQueryChange: (query: string) => void;
}) {
    const t = useTranslations('Actions');
    const tSearch = useTranslations('CommonSearchBar');
    const router = useRouter();
    const { run, pendingIds } = useActions();
    const available = useAvailableActions();

    const [results, setResults] = useState<ScopedResults | null>(null);

    const trimmed = query.trim();
    const lowerQuery = trimmed.toLowerCase();

    useEffect(() => {
        if (trimmed.length < MIN_QUERY_LENGTH) return;
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
    }, [trimmed]);

    const commandGroups = useMemo(() => {
        const groupsToScan = lowerQuery ? ACTION_GROUPS : EMPTY_GROUP_ORDER;
        return groupsToScan
            .map((group) => {
                const actions = available.filter(
                    (action) => action.group === group && (!lowerQuery || actionSearchText(action, t).includes(lowerQuery)),
                );
                if (lowerQuery) {
                    actions.sort((a, b) => rankAction(a, lowerQuery, t) - rankAction(b, lowerQuery, t));
                }
                return { group, actions };
            })
            .filter((entry) => entry.actions.length > 0);
    }, [available, lowerQuery, t]);

    const searching = trimmed.length >= MIN_QUERY_LENGTH && results?.query !== trimmed;
    const recordGroups = trimmed.length >= MIN_QUERY_LENGTH && results?.query === trimmed
        ? buildSearchGroups(results.data, tSearch)
        : [];

    const commandCount = commandGroups.reduce((sum, entry) => sum + entry.actions.length, 0);
    const recordCount = recordGroups.reduce((sum, group) => sum + group.rows.length, 0);
    const showNoResults = trimmed.length > 0 && !searching && commandCount + recordCount === 0;

    const runAction = (id: string) => {
        void run(id, { source: 'palette' });
        onOpenChange(false);
    };

    const goToRecord = (href: string, external?: boolean) => {
        openResult(router, href, external);
        onOpenChange(false);
    };

    return (
        <CommandDialog
            open={open}
            onOpenChange={onOpenChange}
            title={t('palette.trigger')}
            description={t('palette.placeholder')}
            commandProps={{ shouldFilter: false, loop: true }}
        >
            <CommandInput value={query} onValueChange={onQueryChange} placeholder={t('palette.placeholder')} />
            <CommandList>
                {commandGroups.map((entry) => (
                    <CommandGroup key={entry.group} heading={t(`group.${entry.group}`)}>
                        {entry.actions.map((action) => {
                            const Icon = action.icon;
                            const pending = pendingIds.has(action.id);
                            return (
                                <CommandItem
                                    key={action.id}
                                    value={action.id}
                                    disabled={pending}
                                    onSelect={() => runAction(action.id)}
                                >
                                    {pending ? (
                                        <Loader2Icon className="size-4 animate-spin text-muted-foreground" />
                                    ) : Icon ? (
                                        <Icon className="size-4 text-muted-foreground" />
                                    ) : null}
                                    <span className="flex-1 truncate">{t(action.labelKey)}</span>
                                    {action.shortcut ? <CommandShortcut>{formatShortcut(action.shortcut)}</CommandShortcut> : null}
                                </CommandItem>
                            );
                        })}
                    </CommandGroup>
                ))}

                {commandGroups.length > 0 && recordGroups.length > 0 ? <CommandSeparator /> : null}

                {recordGroups.map((group) => (
                    <CommandGroup key={group.key} heading={group.heading}>
                        {group.rows.map((row) => {
                            const RowIcon = row.icon;
                            return (
                                <CommandItem key={row.key} value={row.key} onSelect={() => goToRecord(row.href, row.external)}>
                                    <span className="flex size-6 shrink-0 items-center justify-center">
                                        {row.leading ? (
                                            row.leading
                                        ) : row.accent ? (
                                            <span
                                                className="size-3.5 rounded-full ring-1 ring-border"
                                                style={{ backgroundColor: row.accent }}
                                            />
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
                        {t('palette.loading')}
                    </div>
                ) : null}

                {showNoResults ? (
                    <div className="py-8 text-center text-sm text-muted-foreground">
                        {t('palette.noResults', { query: trimmed })}
                    </div>
                ) : null}
            </CommandList>
        </CommandDialog>
    );
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

/** Ranks a command so label prefix matches sort above mere substring/alias matches. */
function rankAction(action: AppAction, lowerQuery: string, t: (key: string) => string): number {
    return t(action.labelKey).toLowerCase().startsWith(lowerQuery) ? 0 : 1;
}
