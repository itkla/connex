// @vitest-environment jsdom
import { act, type ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DealActionsMenu from '@/app/components/records/deals/DealActionsMenu';
import { TooltipProvider } from '@/components/ui/tooltip';
import type { Deal } from '@/app/lib/types';
import enDeals from '@/messages/en/deals.json';

declare global {
    var IS_REACT_ACT_ENVIRONMENT: boolean;
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }) }));
vi.mock('@/app/components/attachments/useAttachmentUploader', () => ({
    useAttachmentUploader: () => ({
        inputRef: { current: null }, uploading: false, openPicker: vi.fn(), onFilesSelected: vi.fn(),
    }),
}));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => vi.fn() }));
const granted = vi.hoisted(() => ({ permissions: new Set<string>() }));
vi.mock('@/app/hooks/usePermissions', () => ({
    usePermission: (permission: string) => granted.permissions.has(permission),
}));
vi.mock('@/app/hooks/useRecordTargetSearch', () => ({
    useContactTargetSearch: () => ({}),
    useDealTargetSearch: () => ({}),
}));
vi.mock('@/app/components/records/DeleteRecordDialog', () => ({ default: () => null }));
vi.mock('@/app/components/records/deals/DealTeamDialog', () => ({ default: () => null }));
vi.mock('@/app/components/records/deals/EditDealSheet', () => ({ default: () => null }));
vi.mock('@/app/components/activity/notes/NoteDialog', () => ({ default: () => null }));
vi.mock('@/app/components/records/RecordComposers', () => ({
    RecordActivityComposer: () => null,
    RecordTaskComposer: () => null,
}));
vi.mock('@/components/ui/dropdown-menu', async () => {
    const React = await import('react');
    const Passthrough = ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children);
    return {
        DropdownMenu: Passthrough,
        DropdownMenuContent: Passthrough,
        DropdownMenuItem: Passthrough,
        DropdownMenuSeparator: () => null,
        DropdownMenuTrigger: Passthrough,
    };
});

const t = enDeals.DealsActionsMenu;
const deal = { id: 5, name: 'Renewal', company: 3, stageId: 2, pipelineId: 1, value: 1000 } as unknown as Deal;

let container: HTMLDivElement;
let root: Root;

beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
});

const ALL = ['DEAL_UPDATE', 'DEAL_DELETE', 'ATTACHMENT_CREATE', 'ACTIVITY_CREATE', 'NOTE_CREATE', 'TASK_CREATE'];

async function renderMenu(...permissions: string[]) {
    granted.permissions = new Set(permissions);
    await act(async () => root.render(
        <NextIntlClientProvider locale="en" messages={enDeals}>
            <TooltipProvider>
            <DealActionsMenu
                deal={deal}
                pipelines={[]}
                stagesByPipeline={{}}
                currentUserId={11}
            />
            </TooltipProvider>
        </NextIntlClientProvider>,
    ));
}

function labels(): string[] {
    return [...container.querySelectorAll('button, span')]
        .map((element) => element.textContent?.trim() ?? '')
        .filter((label) => label.length > 0);
}

function offers(label: string): boolean {
    return labels().includes(label);
}

describe('the deal detail actions follow the member\'s live effective permissions', () => {
    it('offers no mutation to a read-only member, and drops the empty menus', async () => {
        await renderMenu();

        for (const label of [t.edit, t.manageTeam, t.markWon, t.markLost, t.delete, t.add,
            t.new, t.addActivity, t.addNote, t.addTask]) {
            expect(offers(label)).toBe(false);
        }
        expect(container.querySelector(`[aria-label="${t.moreActions}"]`)).toBeNull();
        expect(offers(t.viewInMap)).toBe(true);
    });

    it('withholds editing, team and outcome changes from a member without DEAL_UPDATE', async () => {
        await renderMenu(...ALL.filter((permission) => permission !== 'DEAL_UPDATE'));

        expect(offers(t.edit)).toBe(false);
        expect(offers(t.manageTeam)).toBe(false);
        expect(offers(t.markWon)).toBe(false);
        expect(offers(t.delete)).toBe(true);
        expect(offers(t.add)).toBe(true);
    });

    it('withholds deletion from a member without DEAL_DELETE', async () => {
        await renderMenu(...ALL.filter((permission) => permission !== 'DEAL_DELETE'));

        expect(offers(t.edit)).toBe(true);
        expect(offers(t.manageTeam)).toBe(true);
        expect(offers(t.markWon)).toBe(true);
        expect(offers(t.delete)).toBe(false);
    });

    it('offers each New item only with its record\'s create permission', async () => {
        await renderMenu('NOTE_CREATE');

        expect(offers(t.new)).toBe(true);
        expect(offers(t.addNote)).toBe(true);
        expect(offers(t.addActivity)).toBe(false);
        expect(offers(t.addTask)).toBe(false);
    });

    it('offers every action to a member holding them all', async () => {
        await renderMenu(...ALL);

        for (const label of [t.edit, t.manageTeam, t.markWon, t.markLost, t.delete, t.add,
            t.new, t.addActivity, t.addNote, t.addTask]) {
            expect(offers(label)).toBe(true);
        }
    });
});
