'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { motion, useReducedMotion } from 'motion/react';
import { MagnifyingGlassIcon, XMarkIcon } from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';

export type MapSearchItem = { id: string; label: string; kind: 'company' | 'contact' | 'user' };

/** Best match for a query: exact › prefix › substring, tie-broken by the shortest (closest) label. */
function bestMatch(items: MapSearchItem[], q: string): MapSearchItem | null {
    if (!q) return null;
    let best: MapSearchItem | null = null;
    let bestScore = 0;
    for (const it of items) {
        const label = it.label.toLowerCase();
        const score = label === q ? 3 : label.startsWith(q) ? 2 : label.includes(q) ? 1 : 0;
        if (score === 0) continue;
        if (score > bestScore || (score === bestScore && best !== null && it.label.length < best.label.length)) {
            best = it;
            bestScore = score;
        }
    }
    return best;
}

/**
 * Map search control, styled and animated like the time-travel control (a rounded pill that
 * morph-expands). Pressing Enter pans the map to the best-matching node and highlights the path to
 * it — the same active-tree highlight hovering a node produces.
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
    const inputRef = useRef<HTMLInputElement>(null);

    const match = useMemo(() => bestMatch(items, query.trim().toLowerCase()), [items, query]);

    useEffect(() => {
        if (open) inputRef.current?.focus();
    }, [open]);

    const submit = () => {
        if (!match) return;
        setQuery(match.label);
        onSelect(match.id);
        inputRef.current?.blur();
    };

    const collapse = () => {
        setOpen(false);
        setQuery('');
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
                <div className="flex items-center gap-1">
                    <MagnifyingGlassIcon className="ml-2 size-4 shrink-0 text-muted-foreground" />
                    <input
                        ref={inputRef}
                        value={query}
                        onChange={(e) => {
                            setQuery(e.target.value);
                            if (activeId) onClear();
                        }}
                        onKeyDown={(e) => {
                            if (e.key === 'Enter') {
                                e.preventDefault();
                                submit();
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
                </div>
            )}
        </motion.div>
    );
}
