"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { Note, NotePageCursor } from "@/app/lib/types";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";

export const NOTE_PAGE_SIZE = 25;
export type NotePageLoader = (page: number, init: RequestInit, cursor?: NotePageCursor) => Promise<Note[]>;

function pageCursor(notes: Note[] | undefined): NotePageCursor | undefined {
    const last = notes?.at(-1);
    const beforeAt = last?.updatedAt || last?.createdAt;
    return last && beforeAt ? { beforeAt, beforeId: last.id } : undefined;
}

function initialPageState(initialNotes: Note[] | undefined, canLoad: boolean) {
    return {
        initialNotes,
        notes: initialNotes ?? [],
        loading: initialNotes === undefined && canLoad,
        hasMore: canLoad && (initialNotes === undefined || initialNotes.length === NOTE_PAGE_SIZE),
        failed: false,
        cursor: pageCursor(initialNotes),
        nextPage: initialNotes === undefined ? 1 : 2,
    };
}

/** Resets continuation on refreshed server data; the owner remounts on record/workspace changes. */
export function useNotePages(loadPage: NotePageLoader | undefined, initialNotes?: Note[]) {
    const [state, setState] = useState(() => initialPageState(initialNotes, loadPage !== undefined));
    if (state.initialNotes !== initialNotes) {
        setState(initialPageState(initialNotes, loadPage !== undefined));
    }
    const pending = useRef<AbortController | null>(null);
    const showApiError = useApiErrorToast();

    const fetchPage = useCallback((page: number, cursor?: NotePageCursor) => {
        if (!loadPage || pending.current) return;
        const controller = new AbortController();
        pending.current = controller;
        return Promise.resolve().then(() => loadPage(page, { signal: controller.signal }, cursor)).then((result) => {
            if (controller.signal.aborted) return;
            setState((previous) => previous.initialNotes !== initialNotes ? previous : {
                ...previous,
                notes: [...new Map([...previous.notes, ...result].map((note) => [note.id, note])).values()],
                hasMore: result.length === NOTE_PAGE_SIZE,
                failed: false,
                nextPage: page + 1,
                cursor: pageCursor(result),
            });
        }).catch((error: unknown) => {
            if (controller.signal.aborted) return;
            setState((previous) => previous.initialNotes !== initialNotes ? previous : { ...previous, failed: true });
            showApiError(error);
        }).finally(() => {
            if (!controller.signal.aborted) {
                pending.current = null;
                setState((previous) => previous.initialNotes !== initialNotes ? previous : { ...previous, loading: false });
            }
        });
    }, [loadPage, showApiError, initialNotes]);

    useEffect(() => () => {
        pending.current?.abort();
        pending.current = null;
    }, [initialNotes]);

    useEffect(() => {
        if (initialNotes === undefined) void fetchPage(1);
    }, [fetchPage, initialNotes]);

    const loadMore = () => {
        if (pending.current || !state.hasMore) return;
        setState((previous) => ({ ...previous, loading: true }));
        void fetchPage(state.nextPage, state.cursor);
    };

    return { notes: state.notes, loading: state.loading, hasMore: state.hasMore, failed: state.failed, loadMore };
}
