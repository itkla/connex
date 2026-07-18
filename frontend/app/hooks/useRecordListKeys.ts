'use client';

import { useCallback, useEffect, useState } from 'react';

function isTextEntry(target: EventTarget | null): boolean {
    if (!(target instanceof HTMLElement)) return false;
    const tag = target.tagName;
    return (
        tag === 'INPUT' ||
        tag === 'TEXTAREA' ||
        tag === 'SELECT' ||
        target.isContentEditable ||
        target.getAttribute('role') === 'textbox'
    );
}

type Options = {
    orderedIds: number[];
    peekOpen: boolean;
    onOpen: (id: number) => void;
    onPrev: () => void;
    onNext: () => void;
};

/**
 * Keyboard row navigation for the records browsers: `J`/`K` move a highlighted active row (or, when
 * a Peek is open, step it prev/next through the visible order), and `Space` opens the active row in
 * Peek. Bare keys are ignored while focus is in a text field, when a modifier is held, or on key
 * repeat, so they never fight typing or the palette. Returns the active row id for the highlight.
 */
export function useRecordListKeys({ orderedIds, peekOpen, onOpen, onPrev, onNext }: Options) {
    const [activeId, setActiveId] = useState<number | null>(null);

    const move = useCallback(
        (delta: -1 | 1) => {
            setActiveId((current) => {
                if (orderedIds.length === 0) return null;
                if (current === null) return orderedIds[delta === 1 ? 0 : orderedIds.length - 1];
                const index = orderedIds.indexOf(current);
                if (index < 0) return orderedIds[0];
                const next = index + delta;
                if (next < 0 || next >= orderedIds.length) return current;
                return orderedIds[next];
            });
        },
        [orderedIds],
    );

    useEffect(() => {
        const onKeyDown = (event: KeyboardEvent) => {
            if (event.repeat || event.metaKey || event.ctrlKey || event.altKey) return;
            if (isTextEntry(event.target)) return;
            const key = event.key.toLowerCase();
            if (key === 'j') {
                event.preventDefault();
                if (peekOpen) onNext();
                else move(1);
            } else if (key === 'k') {
                event.preventDefault();
                if (peekOpen) onPrev();
                else move(-1);
            } else if (key === ' ' && !peekOpen && activeId !== null) {
                event.preventDefault();
                onOpen(activeId);
            }
        };
        document.addEventListener('keydown', onKeyDown);
        return () => document.removeEventListener('keydown', onKeyDown);
    }, [peekOpen, activeId, move, onOpen, onPrev, onNext]);

    return { activeId, setActiveId };
}
