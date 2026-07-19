'use client';

import { useEffect, useState } from 'react';

import { getCompaniesPage, getContactsPage } from '@/app/lib/api';

/** Which record kind a duplicate-name probe is looking up. */
export type DuplicateKind = 'company' | 'person';

/** A record that may be a duplicate of the name being typed. */
export interface DuplicateMatch {
    id: number;
    name: string;
    detail: string | null;
}

/** The matches for the current name plus the total count the server reports. */
export interface DuplicateNameResult {
    matches: DuplicateMatch[];
    total: number;
}

const DEBOUNCE_MS = 250;
const MIN_LENGTH = 3;
const FETCH_SIZE = 25;
const MAX_MATCHES = 4;

const EMPTY: DuplicateNameResult = { matches: [], total: 0 };

/**
 * Debounced, non-blocking lookup for records whose NAME matches the one being typed during creation, so a
 * user can spot a likely duplicate before adding it. The paged search endpoint matches the query across
 * several columns (name, website, email, company, …), so results are filtered client-side to those whose
 * own name actually contains the query — otherwise a company address or a contact's employer would surface
 * as a bogus "duplicate". Failures and aborts are swallowed; this is an advisory hint, never a gate.
 */
export function useDuplicateNameCheck(kind: DuplicateKind, name: string): DuplicateNameResult {
    const [result, setResult] = useState<DuplicateNameResult>(EMPTY);
    const trimmed = name.trim();

    useEffect(() => {
        if (trimmed.length < MIN_LENGTH) {
            // eslint-disable-next-line react-hooks/set-state-in-effect
            setResult((prev) => (prev.matches.length === 0 ? prev : EMPTY));
            return;
        }
        let cancelled = false;
        const controller = new AbortController();
        const needle = trimmed.toLowerCase();
        const timer = setTimeout(() => {
            void (async () => {
                try {
                    const matches: DuplicateMatch[] = [];
                    if (kind === 'company') {
                        const page = await getCompaniesPage({ q: trimmed, size: FETCH_SIZE }, { signal: controller.signal });
                        for (const c of page.items) {
                            if (c.name.toLowerCase().includes(needle)) matches.push({ id: c.id, name: c.name, detail: c.website || c.industry || null });
                        }
                    } else {
                        const page = await getContactsPage({ q: trimmed, size: FETCH_SIZE }, { signal: controller.signal });
                        for (const p of page.items) {
                            if (p.name.toLowerCase().includes(needle)) matches.push({ id: p.id, name: p.name, detail: p.email || p.company?.name || null });
                        }
                    }
                    if (cancelled) return;
                    setResult({ matches: matches.slice(0, MAX_MATCHES), total: matches.length });
                } catch {}
            })();
        }, DEBOUNCE_MS);
        return () => {
            cancelled = true;
            clearTimeout(timer);
            controller.abort();
        };
    }, [kind, trimmed]);

    return result;
}
