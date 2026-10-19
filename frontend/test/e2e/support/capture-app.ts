import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import next from 'next';

import type { InstanceCapabilities, MyWorkspaces, User } from '@/app/lib/types';

const user: User = {
    id: 1,
    username: 'capture-evaluator',
    displayName: 'Capture Evaluator',
    email: 'capture-evaluator@example.com',
    timezone: 'UTC',
    locale: 'en',
    createdAt: '2026-07-30T08:00:00Z',
    updatedAt: '2026-07-30T08:00:00Z',
};

const workspaces: MyWorkspaces = {
    activeWorkspaceId: 1,
    workspaces: [{
        id: 1,
        name: 'Capture evaluator',
        slug: 'capture-evaluator',
        timezone: 'UTC',
        identityVersion: 1,
        role: 'member',
        orgId: 1,
        orgName: 'Capture evaluator',
        orgIdentityVersion: 1,
        orgRole: null,
    }],
};

function serveApi(request: IncomingMessage, response: ServerResponse): void {
    const pathname = new URL(request.url ?? '/', 'http://localhost').pathname;
    let body: unknown;
    switch (pathname) {
        case '/api/capabilities':
            body = {
                sso: false,
                socialLogin: { google: false, microsoft: false },
                connectedAccounts: { google: true, microsoft: false },
                connectedAccountModes: { google: 'custom', microsoft: 'custom' },
                connectedCapture: {
                    google: /(?:^|;\s*)e2e_capture=on(?:;|$)/.test(request.headers.cookie ?? ''),
                    microsoft: false,
                },
                mailManaged: false,
                businessCardScanning: false,
                businessCardImport: false,
                campaignDelivery: false,
                workflowTriggeredSend: false,
                privilegedMfaEnforced: false,
            } satisfies InstanceCapabilities;
            break;
        case '/api/auth/me':
            body = user;
            break;
        case '/api/workspaces':
            body = workspaces;
            break;
        case '/api/permissions/effective':
        case '/api/saved-views/pins':
            body = [];
            break;
        case '/api/auth/csrf':
            body = { token: 'capture-e2e-csrf' };
            break;
        case '/api/notifications/counts':
            body = { unread: 0, snoozed: 0, stateVersion: 0, asOf: user.createdAt, quietHoursActive: false };
            break;
        default:
            response.writeHead(404).end();
            return;
    }
    response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(body));
}

const server = createServer();
server.listen(0, '127.0.0.1', async () => {
    const address = server.address();
    if (address === null || typeof address === 'string') throw new Error('No capture app address');
    const baseURL = `http://127.0.0.1:${address.port}`;
    process.env.API_URL = baseURL;
    const app = next({ dev: false, hostname: '127.0.0.1', port: address.port, httpServer: server });
    const handle = app.getRequestHandler();
    server.on('request', (request, response) => {
        if (request.url?.startsWith('/api/')) {
            serveApi(request, response);
        } else {
            void handle(request, response);
        }
    });
    await app.prepare();
    const warmup = await fetch(`${baseURL}/settings/personal/connected-accounts`, {
        headers: { cookie: 'JSESSIONID=capture-e2e; connex_workspace=1; e2e_capture=on' },
    });
    if (!warmup.ok) throw new Error(`Capture app warmup failed (${warmup.status})`);
    await warmup.text();
    process.send?.({ baseURL });
});
