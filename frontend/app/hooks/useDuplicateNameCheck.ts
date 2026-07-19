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
const MIN_LENGTH = 2;
const MAX_MATCHES = 4;

/**
 * Debounced, non-blocking lookup for records whose name matches the one being typed during creation, so a
 * user can spot a likely duplicate before adding it. Probes the existing paged search endpoint (a substring
 * match over the whole workspace, not just a loaded slice) and returns up to a handful of matches. Failures
 * and aborts are swallowed — this is an advisory hint, never a gate.
 */
export function useDuplicateNameCheck(kind: DuplicateKind, name: string): DuplicateNameResult {
    const [result, setResult] = useState<DuplicateNameResult>({ matches: [], total: 0 });
    const trimmed = name.trim();

    useEffect(() => {
        let cancelled = false;
        const controller = new AbortController();
        const timer = setTimeout(() => {
            void (async () => {
                if (trimmed.length < MIN_LENGTH) {
                    if (!cancelled) setResult({ matches: [], total: 0 });
                    return;
                }
                try {
                    if (kind === 'company') {
                        const page = await getCompaniesPage({ q: trimmed, size: MAX_MATCHES }, { signal: controller.signal });
                        if (cancelled) return;
                        setResult({
                            matches: page.items.map((c) => ({ id: c.id, name: c.name, detail: c.website || c.industry || null })),
                            total: page.total,
                        });
                    } else {
                        const page = await getContactsPage({ q: trimmed, size: MAX_MATCHES }, { signal: controller.signal });
                        if (cancelled) return;
                        setResult({
                            matches: page.items.map((p) => ({ id: p.id, name: p.name, detail: p.email || p.company?.name || null })),
                            total: page.total,
                        });
                    }
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
