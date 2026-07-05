'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { motion, useReducedMotion } from 'motion/react';
import {
    BuildingOffice2Icon,
    MagnifyingGlassIcon,
    UserIcon,
    XMarkIcon,
} from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';

import { cn } from '@/lib/utils';

export type MapSearchItem = { id: string; label: string; kind: 'company' | 'contact' | 'user' };

const MAX_RESULTS = 8;

/**
 * Map search control, styled and animated like the time-travel control (a rounded pill that
 * morph-expands). Selecting a result highlights the path to that node — the same active-tree
 * highlight hovering a node produces — and pans the map to it.
 */
export default function MapSearch({
    items,
    activeId,
    onSelect,
    onClear,
}: {
    items: MapSearchItem[];
    activeId: string | null;
    onSelect: (id: string) => void;
    onClear: () => void;
}) {
    const t = useTranslations('MapSearch');
    const reduce = useReducedMotion();
    const [open, setOpen] = useState(false);
    const [query, setQuery] = useState('');
    const [showResults, setShowResults] = useState(false);
    const inputRef = useRef<HTMLInputElement>(null);

    const results = useMemo(() => {
        const q = query.trim().toLowerCase();
        if (!q) return [];
        return items.filter((it) => it.label.toLowerCase().includes(q)).slice(0, MAX_RESULTS);
    }, [items, query]);

    useEffect(() => {
        if (open) inputRef.current?.focus();
    }, [open]);

    const pick = (it: MapSearchItem) => {
        setQuery(it.label);
        setShowResults(false);
        onSelect(it.id);
        inputRef.current?.blur();
    };

    const collapse = () => {
        setOpen(false);
        setQuery('');
        setShowResults(false);
        onClear();
    };

    return (
        <motion.div
            layout
            transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 380, damping: 36 }}
            className="pointer-events-auto flex items-center gap-1 rounded-full border border-border bg-card/95 p-1 shadow-xl backdrop-blur"
        >
            {!open ? (
                <button
                    type="button"
                    onClick={() => setOpen(true)}
                    aria-label={t('open')}
                    className="flex size-8 items-center justify-center rounded-full text-foreground transition-transform duration-150 ease-out hover:bg-muted active:scale-95"
                >
                    <MagnifyingGlassIcon className="size-4" />
                </button>
            ) : (
                <div className="relative flex items-center gap-1">
                    <MagnifyingGlassIcon className="ml-2 size-4 shrink-0 text-muted-foreground" />
                    <input
                        ref={inputRef}
                        value={query}
                        onChange={(e) => {
                            setQuery(e.target.value);
                            setShowResults(true);
                            if (activeId) onClear();
                        }}
                        onFocus={() => setShowResults(true)}
                        onKeyDown={(e) => {
                            if (e.key === 'Enter' && results[0]) {
                                e.preventDefault();
                                pick(results[0]);
                            } else if (e.key === 'Escape') {
                                collapse();
                            }
                        }}
                        placeholder={t('placeholder')}
                        className="h-8 w-52 bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground"
                        aria-label={t('open')}
                    />
                    <button
                        type="button"
                        onClick={collapse}
                        aria-label={t('close')}
                        className="flex size-7 shrink-0 items-center justify-center rounded-full text-muted-foreground transition hover:bg-muted hover:text-foreground active:scale-95"
                    >
                        <XMarkIcon className="size-4" />
                    </button>

                    {showResults && results.length > 0 && (
                        <div className="absolute top-full right-0 mt-2 w-64 overflow-hidden rounded-xl border border-border bg-popover p-1 text-popover-foreground shadow-xl">
                            {results.map((it) => {
                                const Icon = it.kind === 'company' ? BuildingOffice2Icon : UserIcon;
                                return (
                                    <button
                                        key={it.id}
                                        type="button"
                                        onClick={() => pick(it)}
                                        className={cn(
                                            'flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm transition-colors hover:bg-muted',
                                            activeId === it.id && 'bg-muted',
                                        )}
                                    >
                                        <Icon className="size-4 shrink-0 text-muted-foreground" />
                                        <span className="truncate">{it.label}</span>
                                    </button>
                                );
                            })}
                        </div>
                    )}
                </div>
            )}
        </motion.div>
    );
}
