'use client';

import {
    createContext,
    useCallback,
    useContext,
    useEffect,
    useMemo,
    useRef,
    useState,
    type ReactNode,
} from 'react';
import { usePathname } from 'next/navigation';
import { useTranslations } from 'next-intl';

/** A single step in the user's navigation trail: the visited route and its resolved label. */
export interface Crumb {
    pathname: string;
    label: string;
}

interface NavTrailContextValue {
    trail: Crumb[];
    setLabelFor: (pathname: string, label: string) => void;
}

const NavTrailContext = createContext<NavTrailContextValue | null>(null);

const STORAGE_KEY = 'connex:nav-trail';
const MAX_TRAIL = 12;

const ROUTE_LABELS: readonly { prefix: string; key: string }[] = [
    { prefix: '/dashboard', key: 'navDashboard' },
    { prefix: '/overview/calendar', key: 'navCalendar' },
    { prefix: '/overview/map', key: 'navMap' },
    { prefix: '/overview/introductions', key: 'navIntroductions' },
    { prefix: '/overview/analytics', key: 'navAnalytics' },
    { prefix: '/overview/reports', key: 'navReports' },
    { prefix: '/overview/reports/goals', key: 'navGoals' },
    { prefix: '/records/companies', key: 'navCompanies' },
    { prefix: '/records/contacts', key: 'navContacts' },
    { prefix: '/records/deals', key: 'navDeals' },
    { prefix: '/records/pipelines', key: 'navPipelines' },
    { prefix: '/records/products', key: 'navProducts' },
    { prefix: '/records/approval-policies', key: 'navApprovalPolicies' },
    { prefix: '/marketing/campaigns', key: 'navCampaigns' },
    { prefix: '/activity/all', key: 'navActivities' },
    { prefix: '/activity/tasks', key: 'navTasks' },
    { prefix: '/activity/notes', key: 'navNotes' },
    { prefix: '/library/documents', key: 'navDocuments' },
    { prefix: '/library/tags', key: 'navTags' },
    { prefix: '/library/files', key: 'navFiles' },
    { prefix: '/notifications', key: 'navNotifications' },
    { prefix: '/users', key: 'navUsers' },
    { prefix: '/workflows', key: 'navWorkflows' },
    { prefix: '/settings', key: 'navSettings' },
    { prefix: '/organization', key: 'navOrganization' },
    { prefix: '/admin/logs', key: 'navAuditLog' },
    { prefix: '/docs', key: 'navDocs' },
];

function matchRoute(pathname: string): { prefix: string; key: string } | null {
    let best: { prefix: string; key: string } | null = null;
    for (const entry of ROUTE_LABELS) {
        if (pathname === entry.prefix || pathname.startsWith(`${entry.prefix}/`)) {
            if (!best || entry.prefix.length > best.prefix.length) best = entry;
        }
    }
    return best;
}

function humanize(pathname: string): string {
    const seg = pathname.split('/').filter(Boolean).pop();
    if (!seg) return '/';
    return seg.charAt(0).toUpperCase() + seg.slice(1).replace(/-/g, ' ');
}

function readStored(): Crumb[] | null {
    try {
        const raw = sessionStorage.getItem(STORAGE_KEY);
        if (!raw) return null;
        const parsed: unknown = JSON.parse(raw);
        if (!Array.isArray(parsed)) return null;
        return parsed.filter(
            (c): c is Crumb =>
                typeof c === 'object' && c !== null && typeof (c as Crumb).pathname === 'string' && typeof (c as Crumb).label === 'string',
        );
    } catch {
        return null;
    }
}

/**
 * Folds a navigation into the trail: a section root restarts the trail, revisiting an existing
 * step truncates back to it, and anything else (a detail/sub page) appends a new step.
 */
function advance(prev: Crumb[], pathname: string, labelFor: (p: string) => string): Crumb[] {
    const label = labelFor(pathname);
    if (prev.length > 0 && prev[prev.length - 1].pathname === pathname) {
        if (prev[prev.length - 1].label === label) return prev;
        const next = prev.slice();
        next[next.length - 1] = { pathname, label };
        return next;
    }
    const route = matchRoute(pathname);
    if (route && route.prefix === pathname) return [{ pathname, label }];
    const existing = prev.findIndex((c) => c.pathname === pathname);
    if (existing >= 0) return prev.slice(0, existing + 1);
    const next = [...prev, { pathname, label }];
    return next.length > MAX_TRAIL ? next.slice(next.length - MAX_TRAIL) : next;
}

/**
 * Tracks the user's actual navigation trail (not the URL hierarchy) so a breadcrumb can show where
 * they came from — e.g. arriving at a company from the map reads "Map / <company>". The trail is
 * persisted per session so it survives reloads.
 */
export function NavTrailProvider({ children }: { children: ReactNode }) {
    const pathname = usePathname();
    const t = useTranslations('CommonSidebar');
    const [trail, setTrail] = useState<Crumb[]>([]);
    const overridesRef = useRef<Record<string, string>>({});
    const initializedRef = useRef(false);

    const labelFor = useCallback(
        (path: string): string => {
            const override = overridesRef.current[path];
            if (override) return override;
            const route = matchRoute(path);
            return route ? t(route.key) : humanize(path);
        },
        [t],
    );

    useEffect(() => {
        setTrail((prev) => {
            let base = prev;
            if (!initializedRef.current) {
                initializedRef.current = true;
                const stored = readStored();
                if (stored && stored.length > 0) base = stored;
            }
            return advance(base, pathname, labelFor);
        });
    }, [pathname, labelFor]);

    useEffect(() => {
        if (!initializedRef.current) return;
        try {
            sessionStorage.setItem(STORAGE_KEY, JSON.stringify(trail));
        } catch {
            return;
        }
    }, [trail]);

    const setLabelFor = useCallback((path: string, label: string) => {
        if (!label || overridesRef.current[path] === label) return;
        overridesRef.current = { ...overridesRef.current, [path]: label };
        setTrail((prev) => {
            const idx = prev.findIndex((c) => c.pathname === path);
            if (idx < 0 || prev[idx].label === label) return prev;
            const next = prev.slice();
            next[idx] = { pathname: path, label };
            return next;
        });
    }, []);

    const value = useMemo<NavTrailContextValue>(() => ({ trail, setLabelFor }), [trail, setLabelFor]);

    return <NavTrailContext.Provider value={value}>{children}</NavTrailContext.Provider>;
}

/** The current navigation trail. Empty until the provider has resolved the first route. */
export function useNavTrail(): Crumb[] {
    return useContext(NavTrailContext)?.trail ?? [];
}

/**
 * Overrides the current page's breadcrumb label — for detail pages that want the entity name
 * (e.g. a company's name) rather than the section label the route resolves to.
 */
export function useCrumbLabel(label: string): void {
    const pathname = usePathname();
    const ctx = useContext(NavTrailContext);
    const setLabelFor = ctx?.setLabelFor;
    useEffect(() => {
        if (setLabelFor) setLabelFor(pathname, label);
    }, [setLabelFor, pathname, label]);
}

/** Renders nothing; sets the current page's breadcrumb label. Usable from Server Components. */
export function CrumbLabel({ value }: { value: string }): null {
    useCrumbLabel(value);
    return null;
}
