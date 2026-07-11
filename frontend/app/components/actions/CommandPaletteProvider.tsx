'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import CommandPalette from './CommandPalette';

type CommandPaletteContextValue = {
    /** Opens the palette (e.g. from the pointer-visible trigger). */
    open: () => void;
};

const CommandPaletteContext = createContext<CommandPaletteContextValue | null>(null);

/** Access the command palette controls. Throws if used outside {@link CommandPaletteProvider}. */
export function useCommandPalette(): CommandPaletteContextValue {
    const value = useContext(CommandPaletteContext);
    if (!value) throw new Error('useCommandPalette must be used within CommandPaletteProvider');
    return value;
}

/**
 * Owns the command palette's open and query state, installs the global `Cmd/Ctrl+K` shortcut, and
 * renders the palette alongside the app shell. Closing always clears the query so a reopen never
 * restores a stale search. Place it inside the action provider so the palette can read the registry.
 */
export default function CommandPaletteProvider({ children }: { children: ReactNode }) {
    const [open, setOpen] = useState(false);
    const [query, setQuery] = useState('');

    const handleOpenChange = useCallback((next: boolean) => {
        setOpen(next);
        if (!next) setQuery('');
    }, []);

    useEffect(() => {
        const onKeyDown = (event: KeyboardEvent) => {
            if ((event.metaKey || event.ctrlKey) && !event.altKey && (event.key === 'k' || event.key === 'K')) {
                event.preventDefault();
                setOpen((current) => !current);
            }
        };
        document.addEventListener('keydown', onKeyDown);
        return () => document.removeEventListener('keydown', onKeyDown);
    }, []);

    const value = useMemo<CommandPaletteContextValue>(() => ({ open: () => setOpen(true) }), []);

    return (
        <CommandPaletteContext.Provider value={value}>
            {children}
            <CommandPalette open={open} onOpenChange={handleOpenChange} query={query} onQueryChange={setQuery} />
        </CommandPaletteContext.Provider>
    );
}
