import { readFileSync } from 'node:fs';
import path from 'node:path';
import { isValidElement, type ReactElement, type ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import DocsTopBar from '@/app/components/docs/DocsTopBar';
import LandingNav from '@/app/components/landing/LandingNav';
import DocsLayout from '@/app/docs/layout';
import Home from '@/app/page';
import { getCurrentUserFromCookie, getPublicPageUserFromCookie } from '@/app/lib/api';

vi.mock('next/headers', () => ({
    headers: () => Promise.resolve(new Headers({ cookie: 'NEXT_LOCALE=en' })),
}));

vi.mock('next-intl/server', () => ({
    getTranslations: () => Promise.resolve((key: string) => key),
    getLocale: () => Promise.resolve('en'),
}));

const VISITOR_COOKIE = 'NEXT_LOCALE=en';
const SESSION_COOKIE = 'JSESSIONID=session; connex_workspace=1';

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
}

function stubFetchRejecting(error: Error): void {
    vi.stubGlobal('fetch', () => Promise.reject(error));
}

function stubFetchResponding(build: () => Response): void {
    vi.stubGlobal('fetch', () => Promise.resolve(build()));
}

function hasChildren(props: unknown): props is { children?: ReactNode } {
    return typeof props === 'object' && props !== null && 'children' in props;
}

function findByType(node: ReactNode, type: unknown): ReactElement | null {
    if (Array.isArray(node)) {
        for (const child of node) {
            const found = findByType(child, type);
            if (found !== null) {
                return found;
            }
        }
        return null;
    }
    if (!isValidElement(node)) {
        return null;
    }
    if (node.type === type) {
        return node;
    }
    return hasChildren(node.props) ? findByType(node.props.children, type) : null;
}

function readStringProp(props: unknown, name: string): string | undefined {
    if (typeof props !== 'object' || props === null || !(name in props)) {
        return undefined;
    }
    const value = Reflect.get(props, name);
    return typeof value === 'string' ? value : undefined;
}

function readBooleanProp(props: unknown, name: string): boolean | undefined {
    if (typeof props !== 'object' || props === null || !(name in props)) {
        return undefined;
    }
    const value = Reflect.get(props, name);
    return typeof value === 'boolean' ? value : undefined;
}

afterEach(() => {
    vi.unstubAllGlobals();
});

describe('public pages stay readable while the backend is unreachable', () => {
    it('renders the docs shell with the signed-out call to action instead of failing', async () => {
        stubFetchRejecting(new TypeError('fetch failed'));

        const tree = await DocsLayout({ children: null });
        const topBar = findByType(tree, DocsTopBar);

        expect(topBar).not.toBeNull();
        expect(readBooleanProp(topBar?.props, 'authed')).toBe(false);
    });

    it('renders the landing page with the signed-out call to action instead of failing', async () => {
        stubFetchRejecting(new TypeError('fetch failed'));

        const tree = await Home();
        const nav = findByType(tree, LandingNav);

        expect(nav).not.toBeNull();
        expect(readStringProp(nav?.props, 'ctaHref')).toBe('/auth/register');
    });

    it('degrades the public resolver to a signed-out visitor on a transport failure', async () => {
        stubFetchRejecting(new TypeError('fetch failed'));

        await expect(getPublicPageUserFromCookie(VISITOR_COOKIE)).resolves.toBeNull();
        await expect(getPublicPageUserFromCookie(SESSION_COOKIE)).resolves.toBeNull();
    });

    it('still resolves the signed-in user when the backend answers', async () => {
        stubFetchResponding(
            () => new Response(JSON.stringify({ id: 1, email: 'member@connex.test' }), { status: 200 }),
        );

        await expect(getPublicPageUserFromCookie(SESSION_COOKIE)).resolves.toMatchObject({ id: 1 });
    });
});

describe('authenticated surfaces keep the backend-unreachable guard', () => {
    it('propagates a transport failure rather than reporting a logged-out session', async () => {
        stubFetchRejecting(new TypeError('fetch failed'));

        await expect(getCurrentUserFromCookie(SESSION_COOKIE)).rejects.toThrow(TypeError);
    });

    it('still reports a rejected session as logged out', async () => {
        stubFetchResponding(() => new Response('Unauthorized', { status: 401 }));

        await expect(getCurrentUserFromCookie(SESSION_COOKIE)).resolves.toBeNull();
    });

    it('leaves the app shell on the guarded resolver', () => {
        const layout = source('app/(app)/layout.tsx');

        expect(layout).toContain('getCurrentUserFromCookie');
        expect(layout).not.toContain('getPublicPageUserFromCookie');
    });

    it('leaves the invite pages on the guarded resolver', () => {
        for (const page of ['app/invite/[token]/page.tsx', 'app/invite-link/[token]/page.tsx']) {
            expect(source(page)).toContain('getCurrentUserFromCookie');
            expect(source(page)).not.toContain('getPublicPageUserFromCookie');
        }
    });
});
