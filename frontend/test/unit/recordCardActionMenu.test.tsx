// @vitest-environment jsdom
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { act, type ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { NextIntlClientProvider } from 'next-intl';
import { AppRouterContext, type AppRouterInstance } from 'next/dist/shared/lib/app-router-context.shared-runtime';
import { PathnameContext, SearchParamsContext } from 'next/dist/shared/lib/hooks-client-context.shared-runtime';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ContactCard from '@/app/components/records/contacts/ContactCard';
import DealCard from '@/app/components/records/deals/DealCard';
import DealsKanban from '@/app/components/records/deals/DealsKanban';
import { ActionProvider } from '@/app/hooks/useActions';
import { PermissionsProvider } from '@/app/hooks/usePermissions';
import { WorkspaceProvider } from '@/app/hooks/useWorkspace';
import type { Deal, Pipeline, Stage, User, Workspace } from '@/app/lib/types';

declare global {
    var IS_REACT_ACT_ENVIRONMENT: boolean;
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const messages = Object.assign(
    {},
    ...readdirSync(join(process.cwd(), 'messages/en'))
        .filter((file) => file.endsWith('.json'))
        .map((file) => JSON.parse(readFileSync(join(process.cwd(), 'messages/en', file), 'utf8')) as object),
);

const workspace: Workspace = {
    id: 7,
    name: 'Current workspace',
    slug: 'current-workspace',
    timezone: 'UTC',
    identityVersion: 1,
    role: 'member',
    orgId: 3,
    orgName: 'Test organization',
    orgIdentityVersion: 1,
    orgRole: null,
};

const user: User = {
    id: 11,
    username: 'card-reviewer',
    displayName: 'Card Reviewer',
    email: 'reviewer@example.test',
    createdAt: '2026-09-02T00:00:00Z',
    updatedAt: '2026-09-02T00:00:00Z',
    timezone: 'UTC',
    locale: 'en',
};

const router: AppRouterInstance = {
    back: vi.fn(),
    forward: vi.fn(),
    refresh: vi.fn(),
    push: vi.fn(),
    replace: vi.fn(),
    prefetch: vi.fn(),
};

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    Object.defineProperty(window, 'matchMedia', {
        configurable: true,
        value: () => ({
            matches: false,
            media: '',
            onchange: null,
            addEventListener: () => undefined,
            removeEventListener: () => undefined,
            addListener: () => undefined,
            removeListener: () => undefined,
            dispatchEvent: () => false,
        }),
    });
    Object.defineProperty(HTMLElement.prototype, 'hasPointerCapture', {
        configurable: true,
        value: () => false,
    });
    Object.defineProperty(HTMLElement.prototype, 'setPointerCapture', {
        configurable: true,
        value: () => undefined,
    });
    Object.defineProperty(HTMLElement.prototype, 'releasePointerCapture', {
        configurable: true,
        value: () => undefined,
    });
    vi.stubGlobal('ResizeObserver', class {
        observe() {}
        unobserve() {}
        disconnect() {}
    });
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    document.body.querySelectorAll('[data-slot$="-content"]').forEach((element) => element.remove());
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

async function renderCard(card: ReactNode, permissions: readonly string[] = []) {
    await act(async () => {
        root.render(
            <NextIntlClientProvider locale="en" messages={messages}>
                <AppRouterContext.Provider value={router}>
                    <PathnameContext.Provider value="/records/contacts">
                        <SearchParamsContext.Provider value={new URLSearchParams()}>
                            <PermissionsProvider permissions={permissions} status="resolved">
                                <WorkspaceProvider initialWorkspaces={[workspace]} initialActiveId={workspace.id}>
                                    <ActionProvider user={user}>{card}</ActionProvider>
                                </WorkspaceProvider>
                            </PermissionsProvider>
                        </SearchParamsContext.Provider>
                    </PathnameContext.Provider>
                </AppRouterContext.Provider>
            </NextIntlClientProvider>,
        );
    });
}

function kebab(): HTMLButtonElement {
    const trigger = container.querySelector<HTMLButtonElement>('[data-slot="dropdown-menu-trigger"]');
    if (!trigger) throw new Error('record action kebab not found');
    return trigger;
}

async function openKebab(): Promise<HTMLElement> {
    await act(async () => {
        kebab().dispatchEvent(new MouseEvent('pointerdown', { bubbles: true, cancelable: true, button: 0 }));
        kebab().click();
    });
    const content = document.body.querySelector<HTMLElement>('[data-slot="dropdown-menu-content"][data-state="open"]');
    if (!content) throw new Error('open dropdown menu not found');
    return content;
}

async function closeOpenMenu() {
    await act(async () => {
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
}

async function openContextMenu(): Promise<HTMLElement> {
    const trigger = container.querySelector<HTMLElement>('[data-slot="context-menu-trigger"]');
    if (!trigger) throw new Error('record context-menu trigger not found');
    await act(async () => {
        trigger.dispatchEvent(new MouseEvent('contextmenu', {
            bubbles: true,
            cancelable: true,
            button: 2,
            clientX: 40,
            clientY: 40,
        }));
    });
    const content = document.body.querySelector<HTMLElement>('[data-slot="context-menu-content"][data-state="open"]');
    if (!content) throw new Error('open context menu not found');
    return content;
}

function itemLabels(menu: ParentNode): string[] {
    return [...menu.querySelectorAll<HTMLElement>('[role="menuitem"]')]
        .map((item) => item.textContent?.trim() ?? '');
}

function groupedLabels(menu: ParentNode): string[][] {
    const groups: string[][] = [[]];
    for (const child of menu.querySelectorAll<HTMLElement>(':scope > [role="menuitem"], :scope > [role="separator"]')) {
        if (child.getAttribute('role') === 'separator') groups.push([]);
        else groups[groups.length - 1].push(child.textContent?.trim() ?? '');
    }
    return groups.filter((group) => group.length > 0);
}

async function selectItem(label: string) {
    const menu = await openKebab();
    const item = [...menu.querySelectorAll<HTMLElement>('[role="menuitem"]')]
        .find((candidate) => candidate.textContent?.trim() === label);
    if (!item) throw new Error(`menu item ${label} not found`);
    await act(async () => item.click());
}

function deal(id = 29): Deal {
    return {
        id,
        name: 'Renewal',
        value: 125_000,
        actualValue: 0,
        currency: 'USD',
        pipeline: null,
        stage: null,
        position: 0,
        company: null,
        workspaceId: workspace.id,
        createdAt: '2026-09-01T00:00:00Z',
        updatedAt: '2026-09-01T00:00:00Z',
    };
}

async function renderDealKanban(permissions: readonly string[]) {
    const pipeline: Pipeline = {
        id: 3,
        name: 'Sales',
        createdAt: '2026-09-01T00:00:00Z',
        updatedAt: '2026-09-01T00:00:00Z',
    };
    const qualifiedStage: Stage = {
        id: 31,
        name: 'Qualified',
        pipeline: pipeline.id,
        position: 0,
        success: false,
        failure: false,
    };
    const proposalStage: Stage = {
        id: 32,
        name: 'Proposal',
        pipeline: pipeline.id,
        position: 1,
        success: false,
        failure: false,
    };
    const stages = [qualifiedStage, proposalStage];
    const boardDeal = { ...deal(), pipeline: pipeline.id, stage: qualifiedStage.id };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes('/api/deals/board')) {
            return new Response(JSON.stringify([boardDeal]), { status: 200 });
        }
        if (url.includes('/api/deals/risk')) {
            return new Response('[]', { status: 200 });
        }
        throw new Error(`Unexpected request: ${url}`);
    }));

    await renderCard(
        <DealsKanban
            deals={[boardDeal]}
            pipelines={[pipeline]}
            stagesByPipeline={{ [pipeline.id]: stages }}
            companyById={new Map()}
            pipelineById={new Map([[pipeline.id, pipeline]])}
            stageById={new Map(stages.map((stage) => [stage.id, stage]))}
            riskByDealId={new Map()}
            onQuickEdit={() => undefined}
            onDelete={() => undefined}
            onMoved={() => undefined}
            query=""
            filters={{}}
            segmentActive={false}
            segmentIds={null}
            segmentLoading={false}
            segmentError={null}
            currentUserId={user.id}
            revision={0}
            reduce
        />,
        permissions,
    );

    await act(async () => {
        await vi.waitFor(() => {
            expect(container.querySelector('[data-kanban-card]')).not.toBeNull();
        });
    });
}

describe('real record card action menus', () => {
    it('renders identical owned-contact items from the kebab and right-click, with Quick edit in the contact-local group', async () => {
        await renderCard(
            <ContactCard
                id={17}
                workspaceId={workspace.id}
                name="Ada Lovelace"
                email="ada@example.test"
                phone="+1 555 0100"
                onQuickEdit={() => undefined}
                onDelete={() => undefined}
            />,
        );

        const dropdown = await openKebab();
        const dropdownLabels = itemLabels(dropdown);
        const dropdownGroups = groupedLabels(dropdown);
        expect(dropdownGroups).toContainEqual(['Quick edit', 'Send email', 'Copy phone', 'Change company']);
        expect(dropdownGroups).toContainEqual(['Run workflow', 'Copy link to this record']);

        await closeOpenMenu();
        const context = await openContextMenu();
        expect(itemLabels(context)).toEqual(dropdownLabels);
    });

    it('keeps a shared-in contact useful but removes every record mutation from both real menu surfaces', async () => {
        await renderCard(
            <ContactCard
                id={41}
                workspaceId={workspace.id + 1}
                name="Grace Hopper"
                email="grace@example.test"
                phone="+1 555 0141"
                onQuickEdit={() => undefined}
                onDelete={() => undefined}
            />,
        );

        const dropdown = await openKebab();
        const labels = itemLabels(dropdown);
        expect(labels).toContain('Open record');
        expect(labels).toContain('Send email');
        expect(labels).toContain('Copy phone');
        expect(labels).toContain('Copy link to this record');
        expect(labels).not.toContain('Quick edit');
        expect(labels).not.toContain('Change company');
        expect(labels).not.toContain('Run workflow');
        expect(labels).not.toContain('Archive');

        await closeOpenMenu();
        expect(itemLabels(await openContextMenu())).toEqual(labels);
    });

    it('shows only Restore for an archived owned contact', async () => {
        await renderCard(
            <ContactCard
                id={53}
                workspaceId={workspace.id}
                name="Archived contact"
                readOnly
                removeIntent="restore"
                onDelete={() => undefined}
            />,
        );

        expect(itemLabels(await openKebab())).toEqual(['Restore']);
        await closeOpenMenu();
        expect(itemLabels(await openContextMenu())).toEqual(['Restore']);
    });

    it('hides both empty menus for an archived shared-in contact', async () => {
        await renderCard(
            <ContactCard
                id={54}
                workspaceId={workspace.id + 1}
                name="Archived shared-in contact"
                readOnly
                removeIntent="restore"
                onDelete={() => undefined}
            />,
        );

        expect(container.querySelector('[data-slot="dropdown-menu-trigger"]')).toBeNull();
        expect(container.querySelector('[data-slot="context-menu-trigger"]')).toBeNull();
    });

    it.each([
        { permissions: [] as string[], present: [] as string[], absent: ['Quick edit', 'Delete'] },
        { permissions: ['DEAL_UPDATE'], present: ['Quick edit'], absent: ['Delete'] },
        { permissions: ['DEAL_DELETE'], present: ['Delete'], absent: ['Quick edit'] },
    ])('gates deal mutations on their distinct effective permissions: $permissions', async ({ permissions, present, absent }) => {
        await renderCard(
            <DealCard deal={deal()} onQuickEdit={() => undefined} onDelete={() => undefined} />,
            permissions,
        );

        const labels = itemLabels(await openKebab());
        for (const label of present) expect(labels).toContain(label);
        for (const label of absent) expect(labels).not.toContain(label);
        await closeOpenMenu();
        expect(itemLabels(await openContextMenu())).toEqual(labels);
    });

    it.each([
        { permissions: ['DEAL_READ'] as string[], draggable: false },
        { permissions: ['DEAL_UPDATE'], draggable: true },
    ])('gates kanban deal dragging on DEAL_UPDATE: $permissions', async ({ permissions, draggable }) => {
        await renderDealKanban(permissions);

        const kanbanCard = container.querySelector<HTMLElement>('[data-kanban-card]');
        expect(kanbanCard).not.toBeNull();
        expect(kanbanCard?.getAttribute('role')).toBe(draggable ? 'button' : null);
        expect(kanbanCard?.getAttribute('tabindex')).toBe(draggable ? '0' : null);
    });

    it('invokes the owned contact handlers through the real menu items', async () => {
        const quickEdit = vi.fn();
        const sendEmail = vi.fn();
        const copyPhone = vi.fn();
        const changeCompany = vi.fn();
        await renderCard(
            <ContactCard
                id={61}
                workspaceId={workspace.id}
                name="Katherine Johnson"
                email="katherine@example.test"
                phone="+1 555 0161"
                onQuickEdit={quickEdit}
                onSendEmail={sendEmail}
                onCopyPhone={copyPhone}
                onChangeCompany={changeCompany}
            />,
        );

        await selectItem('Quick edit');
        await selectItem('Send email');
        await selectItem('Copy phone');
        await selectItem('Change company');

        expect(quickEdit).toHaveBeenCalledOnce();
        expect(sendEmail).toHaveBeenCalledOnce();
        expect(copyPhone).toHaveBeenCalledOnce();
        expect(changeCompany).toHaveBeenCalledOnce();
    });
});
