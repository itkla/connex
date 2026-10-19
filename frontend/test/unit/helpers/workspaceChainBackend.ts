import { vi } from 'vitest';

import { bindRenderedWorkspace } from '@/app/lib/renderedWorkspace';
import type { Company, Contact, CustomFieldEntry, Deal, Pipeline, Stage } from '@/app/lib/types';

/** The rendered origin used by the chain regressions. */
export const ORIGIN = 9;
/** The explicitly selected workspace while a request is pending. */
export const NEXT = 5;
/** A provider owner shared only within one regression module. */
export const shell = {};
/** A staged picture or logo. */
export const image = new File(['image'], 'board.png', { type: 'image/png' });
/** A contact owned by the origin. */
export const contact: Contact = {
    id: 41, workspaceId: ORIGIN, name: 'Origin contact', email: 'origin@example.test', phone: '', title: '',
    imageUrl: '', createdAt: '', updatedAt: '',
};
/** A company owned by the origin. */
export const company: Company = {
    id: 41, workspaceId: ORIGIN, name: 'Origin company', website: '', industry: '', phone: '', address: '',
    logoUrl: '', createdAt: '', updatedAt: '',
};
/** A pipeline owned by the origin. */
export const pipeline: Pipeline = { id: 41, workspaceId: ORIGIN, name: 'Origin pipeline', createdAt: '', updatedAt: '' };
/** A nonterminal stage in the origin pipeline. */
export const stage: Stage = { id: 42, name: 'Qualify', pipeline: 41, position: 0, success: false, failure: false };
/** A deal owned by the origin. */
export const deal: Deal = {
    id: 41, workspaceId: ORIGIN, name: 'Origin deal', value: 100, actualValue: 0, currency: 'USD',
    pipeline: 41, stage: 42, position: 0, company: null, createdAt: '', updatedAt: '',
};
/** An editable field returned by the real custom-field section's read. */
export const customField: CustomFieldEntry = {
    definitionId: 1, fieldKey: 'region', label: 'Region', fieldType: 'text', options: null, required: false, value: 'East',
};

/** A promise controlled by a test at a request boundary. */
export function deferred<T>() {
    let resolve!: (value: T) => void;
    const promise = new Promise<T>((done) => { resolve = done; });
    return { promise, resolve };
}

/** An ordinary JSON response, including refusal/error responses. */
export function json(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/** Publishes an explicit switch in both the cookie and the rendered shell. */
export function switchWorkspace(): void {
    document.cookie = 'connex_workspace=' + NEXT + '; path=/';
    bindRenderedWorkspace(shell, NEXT);
}

/** Stubs transport only: components and all api.ts request/header logic remain real. */
export function stubBackend(initialHeldPath?: string, respond?: (path: string, method: string) => Response | undefined) {
    const requests: { path: string; workspace: string | null; init: RequestInit }[] = [];
    const held = deferred<Response>();
    const started = deferred<void>();
    let heldPath = initialHeldPath;
    let hold = true;
    function response(path: string, method = 'POST'): Response {
        const custom = respond?.(path, method);
        if (custom) return custom;
        if (path.includes('/duplicate-preflight/')) return json({ candidates: [], truncated: false, reviewToken: 'review-token' });
        if (path.endsWith('/custom-fields')) return json([customField]);
        if (path === '/api/companies') return json(method === 'GET' ? [] : company);
        if (path === '/api/persons') return json(contact);
        if (path === '/api/pipelines') return json(method === 'GET' ? [pipeline] : pipeline);
        if (path === '/api/deals') return json(method === 'GET' ? [] : deal);
        if (path.endsWith('/logo')) return json({ ...company, logoUrl: '/api/logo' });
        if (path.endsWith('/profile-picture')) return json({ ...contact, imageUrl: '/api/picture' });
        if (path.endsWith('/stages')) return json([stage]);
        if (path === '/api/auth/me') return json({ id: 7 });
        if (path === '/api/business-cards/availability') return json({ scanning: true, importing: true });
        if (path === '/api/business-cards/import') return json({ contact, disposition: 'created', company: null });
        if (path === '/api/permissions/effective') return json(['PERSON_CREATE', 'COMPANY_CREATE', 'ATTACHMENT_CREATE']);
        if (path.endsWith('/facets')) return json({ industries: [], owners: [], titles: [], companies: [], warmth: [], status: [], stages: [], pipelines: [], currencies: [], risk: [] });
        if (path.endsWith('/metrics')) return json({ byCurrency: [], totalCount: 0 });
        if (path.endsWith('/page')) return json({ items: [], total: 0 });
        if (path === '/api/attachments/upload-image') return json({ id: 41, url: '/api/attachments/41/content', fileName: image.name });
        if (path.endsWith('/impact')) return json({ changeClass: 'UNCHANGED', pendingApprovalCount: 0 });
        return json(method === 'GET' ? [] : { id: 41 });
    }
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init: RequestInit = {}) => {
        const path = new URL(input instanceof Request ? input.url : String(input), 'http://localhost').pathname;
        const method = init.method ?? 'GET';
        if (path === '/api/auth/csrf') return json({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN', requestIdentity: 'identity' });
        if (method !== 'GET') requests.push({ path, workspace: new Headers(init.headers).get('X-Workspace-Id'), init });
        if (path === heldPath && method !== 'GET' && hold) {
            hold = false;
            started.resolve();
            return held.promise;
        }
        return response(path, method);
    }));
    return {
        requests, held, started, response,
        arm: (path: string) => { requests.length = 0; heldPath = path; },
    };
}
