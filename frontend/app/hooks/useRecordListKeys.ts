'use client';

import { useEffect, useLayoutEffect, useRef, useState } from 'react';

import { isTypingTarget } from '@/app/lib/utils';

const ACTIVATION_TARGETS =
    'button, a[href], summary, [role="button"], [role="checkbox"], [role="switch"], [role="radio"], [role="menuitem"], [role="option"], [role="tab"]';

const FOREIGN_POPUPS =
    '[role="dialog"]:not([data-record-peek]), [role="alertdialog"], [role="menu"], [role="listbox"]';

type Options = {
    orderedIds: number[];
    peekOpen: boolean;
    enabled: boolean;
    onOpen: (id: number) => void;
    onPrev: () => void;
    onNext: () => void;
};

/**
 * Keyboard row navigation for the records browsers: `J`/`K` move a highlighted active row (or, when
 * a Peek is open, step it prev/next through the visible order), and `Space` opens the active row in
 * Peek. Bare keys are ignored while focus is in a text field or combobox, inside any popup other
 * than the Peek drawer itself, when a modifier is held, or on key repeat; `Space` additionally
 * never fires while an activatable control (button, link, checkbox, …) has focus, so it cannot
 * steal the control's own activation. Pass `enabled: false` for display modes without a visible
 * row highlight (grid, kanban) to keep the keys inert. Returns the active row id for the highlight.
 */
export function useRecordListKeys({ orderedIds, peekOpen, enabled, onOpen, onPrev, onNext }: Options) {
    const [activeId, setActiveId] = useState<number | null>(null);

    const stateRef = useRef({ orderedIds, peekOpen, enabled, onOpen, onPrev, onNext, activeId });
    useLayoutEffect(() => {
        stateRef.current = { orderedIds, peekOpen, enabled, onOpen, onPrev, onNext, activeId };
    });

    useEffect(() => {
        const move = (delta: -1 | 1) => {
            const ids = stateRef.current.orderedIds;
            setActiveId((current) => {
                if (ids.length === 0) return null;
                if (current === null) return ids[delta === 1 ? 0 : ids.length - 1];
                const index = ids.indexOf(current);
                if (index < 0) return ids[0];
                const next = index + delta;
                if (next < 0 || next >= ids.length) return current;
                return ids[next];
            });
        };
        const onKeyDown = (event: KeyboardEvent) => {
            const state = stateRef.current;
            if (!state.enabled) return;
            if (event.repeat || event.metaKey || event.ctrlKey || event.altKey) return;
            if (isTypingTarget(event.target)) return;
            const target = event.target instanceof Element ? event.target : null;
            if (target?.closest(FOREIGN_POPUPS)) return;
            const key = event.key.toLowerCase();
            if (key === 'j' || key === 'k') {
                if (target?.closest('[role="combobox"]')) return;
                event.preventDefault();
                if (state.peekOpen) (key === 'j' ? state.onNext : state.onPrev)();
                else move(key === 'j' ? 1 : -1);
            } else if (key === ' ' && !state.peekOpen && state.activeId !== null) {
                if (target?.closest(ACTIVATION_TARGETS)) return;
                event.preventDefault();
                state.onOpen(state.activeId);
            }
        };
        document.addEventListener('keydown', onKeyDown);
        return () => document.removeEventListener('keydown', onKeyDown);
    }, []);

    return { activeId, setActiveId };
}
