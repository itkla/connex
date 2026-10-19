// @vitest-environment jsdom
import { act, createElement, useState, type ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import commonMessages from '@/messages/en/common.json';
import dealsMessages from '@/messages/en/deals.json';
import japaneseDealsMessages from '@/messages/ja/deals.json';

declare global {
    var IS_REACT_ACT_ENVIRONMENT: boolean;
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

import {
    approvalStepApproverChangePayload,
    approvalStepQuorumShortfall,
    manageableApprovalSteps,
    widenableApprovalSteps,
} from '@/app/components/records/deals/approvalStepActions';
import type {
    ApprovalStepCandidate,
    ApprovalStepCandidates,
    DealDocument,
    DocumentApproval,
    DocumentApprovalStep,
    WorkspaceMember,
} from '@/app/lib/types';

const api = vi.hoisted(() => ({
    getDocumentTemplates: vi.fn(),
    getDealDocumentById: vi.fn(),
    getActiveWorkspaceMembers: vi.fn(),
    getDocumentApprovalApproverCandidates: vi.fn<typeof import('@/app/lib/api').getDocumentApprovalApproverCandidates>(),
    reassignDocumentApprovalStepApprovers: vi.fn(),
    widenDocumentApprovalStepApprovers: vi.fn(),
}));
const workspaceContext = vi.hoisted(() => ({ id: 42 }));
const errors = vi.hoisted(() => ({ show: vi.fn() }));
const toasts = vi.hoisted(() => ({ success: vi.fn() }));

vi.mock('next/navigation', () => ({
    useRouter: () => ({ push: vi.fn() }),
}));

vi.mock('@/app/hooks/useApiErrorToast', () => ({
    useApiErrorToast: () => errors.show,
}));

vi.mock('@/app/hooks/useWorkspace', () => ({
    useWorkspace: () => ({ activeWorkspace: { id: workspaceContext.id, role: 'owner' }, activeWorkspaceId: workspaceContext.id }),
}));

vi.mock('@/app/lib/toast', () => ({
    toastSuccess: toasts.success,
}));

vi.mock('@/app/lib/api', () => ({
    ...api,
    generateDealDocument: vi.fn(),
    updateDealDocumentStatus: vi.fn(),
    deleteDealDocument: vi.fn(),
    requestDocumentApproval: vi.fn(),
    decideDocumentApproval: vi.fn(),
    delegateDocumentApproval: vi.fn(),
    cancelDocumentApproval: vi.fn(),
    getDocumentApprovalDelegateCandidates: vi.fn(),
}));

import ApprovalStepApproversDialog from '@/app/components/records/deals/ApprovalStepApproversDialog';
import DealDocuments from '@/app/components/records/deals/DealDocuments';

const TEST_MESSAGES = {
    DealsDocuments: Object.fromEntries(
        Object.keys(dealsMessages.DealsDocuments).map((key) => [key, key]),
    ),
    ConfirmDiscard: Object.fromEntries(
        Object.keys(commonMessages.ConfirmDiscard).map((key) => [key, `ConfirmDiscard.${key}`]),
    ),
};

function TestIntlProvider({ children }: { children?: ReactNode }) {
    const providerProps = {
        locale: 'en',
        timeZone: 'UTC',
        messages: TEST_MESSAGES,
        children,
    };
    return createElement(NextIntlClientProvider, providerProps);
}

function step(id: number, stepOrder: number, status: DocumentApprovalStep['status']): DocumentApprovalStep {
    return {
        id,
        stepOrder,
        name: null,
        requiredCount: 1,
        approvedCount: 0,
        status,
        onExpiry: 'expire',
        satisfiable: true,
        effectiveAnyApprover: false,
        effectiveApproverIds: [],
        approvers: [],
        assignments: [],
        decisions: [],
    };
}

function approval(
    steps: DocumentApprovalStep[],
    overrides: Partial<Pick<DocumentApproval, 'status' | 'mode'>> = {},
): DocumentApproval {
    return {
        id: 30,
        documentId: 20,
        status: 'pending',
        satisfiable: true,
        mode: 'sequential',
        separationOfDuties: 'strict',
        createdAt: '2026-09-02T10:00:00Z',
        steps,
        ...overrides,
    };
}

function documentWith(
    latestApproval: DocumentApproval | null,
    status: DealDocument['status'] = 'pending_approval',
): DealDocument {
    return {
        id: 20,
        dealId: 10,
        type: 'quote',
        locale: 'en',
        status,
        version: 1,
        currency: 'USD',
        generatedAt: '2026-09-02T09:00:00Z',
        createdBy: 1,
        content: {
            generatedAt: '2026-09-02T09:00:00Z',
            deal: { name: 'Renewal', currency: 'USD' },
            sections: {},
            lineItems: [],
            totals: {
                currency: 'USD',
                subtotal: 0,
                tax: 0,
                oneTimeTotal: 0,
                recurringTotal: 0,
                grandTotal: 0,
            },
        },
        requiresApproval: true,
        latestApproval,
    };
}

describe('approval step management availability', () => {
    it('returns no steps without document management permission', () => {
        const document = documentWith(approval([step(1, 1, 'active')]));

        expect(manageableApprovalSteps(document, false)).toEqual([]);
    });

    it('returns no steps when either the document or approval is no longer pending', () => {
        const activeApproval = approval([step(1, 1, 'active')]);

        expect(manageableApprovalSteps(documentWith(activeApproval, 'draft'), true)).toEqual([]);
        expect(manageableApprovalSteps(
            documentWith(approval(activeApproval.steps, { status: 'approved' })),
            true,
        )).toEqual([]);
    });

    it('returns only active steps in step order for a parallel approval', () => {
        const document = documentWith(approval([
            step(4, 4, 'active'),
            step(2, 2, 'approved'),
            step(1, 1, 'active'),
        ], { mode: 'parallel' }));

        expect(manageableApprovalSteps(document, true).map((candidate) => candidate.id)).toEqual([1, 4]);
    });

    it('returns the single active step for a sequential approval', () => {
        const document = documentWith(approval([
            step(1, 1, 'approved'),
            step(2, 2, 'active'),
            step(3, 3, 'active'),
        ]));

        expect(manageableApprovalSteps(document, true).map((candidate) => candidate.id)).toEqual([2]);
    });

    it('omits already-open steps from widening without removing them from reassignment', () => {
        const openStep = { ...step(1, 1, 'active'), effectiveAnyApprover: true };
        const named = step(2, 2, 'active');
        const document = documentWith(approval([openStep, named], { mode: 'parallel' }));

        expect(widenableApprovalSteps(document, true).map((candidate) => candidate.id)).toEqual([2]);
        expect(manageableApprovalSteps(document, true).map((candidate) => candidate.id)).toEqual([1, 2]);
    });
});

describe('approval step management payloads', () => {
    it('emits the exclusive any-approver shape without a user id', () => {
        expect(approvalStepApproverChangePayload({ mode: 'any_approver' }, '  urgent  ')).toEqual({
            approvers: [{ approverKind: 'any_approver' }],
            comment: 'urgent',
        });
    });

    it('emits named approvers, collapses duplicates, and sends a blank comment as null', () => {
        expect(approvalStepApproverChangePayload(
            { mode: 'members', memberIds: [8, 5, 8] },
            '   ',
        )).toEqual({
            approvers: [
                { approverKind: 'user', userId: 8 },
                { approverKind: 'user', userId: 5 },
            ],
            comment: null,
        });
    });

    it('caps the named approver payload at the server limit', () => {
        const memberIds = Array.from({ length: 25 }, (_, index) => index + 1);

        expect(approvalStepApproverChangePayload(
            { mode: 'members', memberIds },
            '',
        ).approvers).toHaveLength(20);
    });
});

describe('approval step quorum validation', () => {
    const snapshot = candidates([member(1), member(2), member(3)], {
        requiredCount: 3,
        approvedCount: 1,
        remainingRequiredCount: 2,
        decidedByUserIds: [1],
        effectiveApproverIds: [1, 2],
    });

    it('counts current undecided approvers for widening but not reassignment', () => {
        expect(approvalStepQuorumShortfall(
            snapshot, 'escalate', { mode: 'members', memberIds: [3] },
        )).toBe(0);
        expect(approvalStepQuorumShortfall(
            snapshot, 'reassign', { mode: 'members', memberIds: [3] },
        )).toBe(1);
    });

    it('uses only server-authorized undecided candidates when every approver is allowed', () => {
        expect(approvalStepQuorumShortfall(
            { ...snapshot, candidates: [member(1), member(2)] },
            'reassign', { mode: 'any_approver' },
        )).toBe(1);
        expect(approvalStepQuorumShortfall(
            snapshot, 'reassign', { mode: 'any_approver' },
        )).toBe(0);
    });

    it('ships the shortfall hint in English and Japanese', () => {
        expect(dealsMessages.DealsDocuments.approvalQuorumShortfall).toContain('server checks');
        expect(japaneseDealsMessages.DealsDocuments.approvalQuorumShortfall).toContain('サーバー');
        for (const messages of [dealsMessages, japaneseDealsMessages]) {
            expect(messages.DealsDocuments.approvalQuorumContext).toContain('{required}');
            expect(messages.DealsDocuments.approvalQuorumContext).toContain('{approved}');
            expect(messages.DealsDocuments.approvalQuorumContext).toContain('{remaining}');
        }
    });
});

function member(
    id: number,
    options: Partial<Pick<WorkspaceMember, 'builtInRole' | 'role' | 'roleId' | 'status'>> = {},
): WorkspaceMember {
    return {
        id,
        username: `member-${id}`,
        displayName: `Member ${id}`,
        email: `member-${id}@example.test`,
        role: options.role ?? 'admin',
        builtInRole: options.builtInRole ?? 'admin',
        roleId: options.roleId ?? null,
        status: options.status ?? 'active',
    };
}

function candidates(
    members: ApprovalStepCandidate[] = [member(1), member(2)],
    counts: Partial<Omit<ApprovalStepCandidates, 'candidates'>> = {},
): ApprovalStepCandidates {
    return { candidates: members, requiredCount: 1, approvedCount: 0, remainingRequiredCount: 1,
        decidedByUserIds: [], effectiveApproverIds: [], ...counts };
}

function deferred<T>() {
    let resolve!: (value: T) => void;
    let reject!: (reason: unknown) => void;
    const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
    return { promise, resolve, reject };
}

function namedStep(
    id: number,
    stepOrder: number,
    approverIds: number[] = [1],
): DocumentApprovalStep {
    return {
        ...step(id, stepOrder, 'active'),
        name: stepOrder === 1 ? 'Finance' : 'Legal',
        effectiveApproverIds: approverIds,
    };
}

function pendingDocument(steps: DocumentApprovalStep[], mode: DocumentApproval['mode'] = 'sequential') {
    return {
        ...documentWith(approval(steps, { mode })),
        title: 'Renewal quote',
    };
}

type DialogHarnessProps = {
    steps: DocumentApprovalStep[];
    initialStepId: number | null;
    members?: WorkspaceMember[];

    action?: 'escalate' | 'reassign';
    initialComment?: string;
    onSubmit?: () => void;
    onClose?: () => void;
};

function DialogHarness({
    steps,
    initialStepId,
    members = [member(1), member(2)],

    action = 'reassign',
    initialComment = '',
    onSubmit = () => undefined,
    onClose = () => undefined,
}: DialogHarnessProps) {
    const [selectedStepId, setSelectedStepId] = useState(initialStepId);
    const [mode, setMode] = useState<'members' | 'any_approver'>('members');
    const [selectedMembers, setSelectedMembers] = useState<ApprovalStepCandidate[]>([]);
    const [comment, setComment] = useState(initialComment);
    return createElement(ApprovalStepApproversDialog, {
        open: true,
        action,
        documentTitle: 'Renewal quote',
        steps,
        selectedStepId,
        memberDirectoryStatus: 'ready',
        members,
        quorum: candidates(members, {
            requiredCount: steps.find((step) => step.id === selectedStepId)?.requiredCount ?? 1,
            approvedCount: steps.find((step) => step.id === selectedStepId)?.approvedCount ?? 0,
            remainingRequiredCount: Math.max(0, (steps.find((step) => step.id === selectedStepId)?.requiredCount ?? 1)
                - (steps.find((step) => step.id === selectedStepId)?.approvedCount ?? 0)),
            decidedByUserIds: steps.find((step) => step.id === selectedStepId)?.decisions
                .map((decision) => decision.decidedBy) ?? [],
            effectiveApproverIds: steps.find((step) => step.id === selectedStepId)?.effectiveApproverIds ?? [],
        }),
        memberLabelStatus: 'ready',
        memberLabels: members,
        mode,
        selectedMembers,
        comment,
        busy: false,
        onOpenChange: (open) => { if (!open) onClose(); },
        onStepChange: (nextStepId) => {
            setSelectedStepId(nextStepId);
            setSelectedMembers([]);
        },
        onRetryMembers: () => undefined,
        onModeChange: (nextMode) => {
            setMode(nextMode);
            setSelectedMembers([]);
        },
        onSelectedMembersChange: setSelectedMembers,
        onCommentChange: setComment,
        onSubmit,
    });
}

describe('approval component lifecycle', () => {
    let container: HTMLDivElement;
    let root: Root;
    let mobileViewport = false;

    beforeEach(() => {
        Object.defineProperty(window, 'matchMedia', {
            configurable: true,
            writable: true,
            value: (query: string) => ({
                matches: query === '(prefers-reduced-motion)'
                    || (query === '(max-width: 767px)' && mobileViewport),
                media: query,
                onchange: null,
                addEventListener: () => undefined,
                removeEventListener: () => undefined,
                addListener: () => undefined,
                removeListener: () => undefined,
                dispatchEvent: () => false,
            }),
        });
        vi.stubGlobal('ResizeObserver', class {
            observe() {}
            unobserve() {}
            disconnect() {}
        });
        vi.stubGlobal('IntersectionObserver', class {
            observe() {}
            unobserve() {}
            disconnect() {}
            takeRecords() { return []; }
        });
        if (typeof PointerEvent === 'undefined') {
            vi.stubGlobal('PointerEvent', class extends MouseEvent {
                pointerId = 1;
                pointerType = 'mouse';
                isPrimary = true;
            });
        }
        Object.defineProperties(HTMLElement.prototype, {
            scrollIntoView: { configurable: true, value: () => undefined },
            hasPointerCapture: { configurable: true, value: () => false },
            releasePointerCapture: { configurable: true, value: () => undefined },
            setPointerCapture: { configurable: true, value: () => undefined },
        });
        container = document.createElement('div');
        document.body.appendChild(container);
        root = createRoot(container);
        vi.clearAllMocks();
        mobileViewport = false;
        api.getDocumentTemplates.mockResolvedValue([]);
        api.getDealDocumentById.mockResolvedValue(pendingDocument([namedStep(1, 1)]));
        api.getActiveWorkspaceMembers.mockResolvedValue(Array.from({ length: 21 }, (_, index) => member(index + 1)));
        workspaceContext.id = 42;
        api.getDocumentApprovalApproverCandidates.mockResolvedValue(candidates(
            Array.from({ length: 21 }, (_, index) => member(index + 1)),
        ));
    });

    afterEach(async () => {
        await act(async () => root.unmount());
        container.remove();
        vi.unstubAllGlobals();
    });

    async function render(node: ReactNode) {
        await act(async () => {
            root.render(createElement(
                TestIntlProvider,
                null,
                node,
            ));
            await Promise.resolve();
        });
    }

    function button(label: string, exact = true): HTMLButtonElement {
        const found = [...document.querySelectorAll<HTMLButtonElement>('button')].find((candidate) => (
            candidate.getAttribute('aria-label') === label
            || (exact ? candidate.textContent === label : candidate.textContent?.includes(label))
        ));
        if (!found) throw new Error(`No button found for ${label}`);
        return found;
    }

    async function click(target: HTMLElement) {
        await act(async () => {
            target.click();
            await Promise.resolve();
        });
    }

    async function pointerClick(target: HTMLElement) {
        await act(async () => {
            target.dispatchEvent(new PointerEvent('pointerdown', {
                bubbles: true,
                button: 0,
                buttons: 1,
                clientX: 1,
                clientY: 1,
            }));
            target.dispatchEvent(new PointerEvent('pointerup', {
                bubbles: true,
                button: 0,
                clientX: 1,
                clientY: 1,
            }));
            target.dispatchEvent(new MouseEvent('click', { bubbles: true, button: 0 }));
            await Promise.resolve();
        });
    }

    async function keyDown(target: HTMLElement, key: string) {
        await act(async () => {
            target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
            await Promise.resolve();
        });
    }

    async function openActionsMenu() {
        await pointerClick(button('actions'));
    }

    function menuItem(label: string): HTMLElement {
        const found = [...document.querySelectorAll<HTMLElement>('[role="menuitem"]')]
            .find((candidate) => candidate.textContent === label);
        if (!found) throw new Error(`No menu item found for ${label}`);
        return found;
    }

    async function chooseMenuAction(label: string) {
        await openActionsMenu();
        await pointerClick(menuItem(label));
    }

    function option(label: string): HTMLElement {
        const found = [...document.querySelectorAll<HTMLElement>('[role="option"]')]
            .find((candidate) => candidate.textContent === label);
        if (!found) throw new Error(`No option found for ${label}`);
        return found;
    }

    async function chooseStep(label: string, currentLabel = 'approvalStepPlaceholder') {
        await pointerClick(button(currentLabel));
        await pointerClick(option(label));
    }

    async function chooseMember(id: number) {
        const input = document.querySelector<HTMLInputElement>('#approval-management-members');
        if (!input) throw new Error('Member picker input not found');
        if (!document.querySelector('[data-slot="combobox-content"]')) {
            await pointerClick(input);
            await keyDown(input, 'ArrowDown');
        }
        const target = [...document.querySelectorAll<HTMLElement>('[data-slot="combobox-item"]')]
            .find((candidate) => candidate.textContent?.includes(`Member ${id}`));
        if (!target) throw new Error(`No member picker option for ${id}`);
        await pointerClick(target);
    }

    describe('ApprovalStepApproversDialog interactions', () => {
        it('requires an explicit approval-step choice for parallel approvals', async () => {
            const onSubmit = vi.fn();
            await render(createElement(DialogHarness, {
                steps: [namedStep(1, 1), namedStep(2, 2)],
                initialStepId: null,
                onSubmit,
            }));

            const submit = button('reassignConfirm');
            expect(button('approvalStepPlaceholder')).toBe(document.activeElement);
            expect(submit.disabled).toBe(true);
            expect(document.querySelector('[data-slot="combobox-item"]')).toBeNull();

            await chooseStep('Legal');
            await click(button('approvalAnyApprover'));

            expect(submit.disabled).toBe(false);
            await click(submit);
            expect(onSubmit).toHaveBeenCalledOnce();
        });

        it('clears named selections whenever the approver mode changes', async () => {
            await render(createElement(DialogHarness, {
                steps: [namedStep(1, 1)],
                initialStepId: 1,
            }));

            await chooseMember(1);
            expect(document.querySelectorAll('[data-slot="combobox-chip"]')).toHaveLength(1);
            await click(button('approvalAnyApprover'));
            await click(button('approvalNamedMembers'));

            expect(document.querySelectorAll('[data-slot="combobox-chip"]')).toHaveLength(0);
            expect(button('reassignConfirm').disabled).toBe(true);
        });

        it('disables submit with zero named approvers and with a comment over 500 characters', async () => {
            await render(createElement(DialogHarness, {
                steps: [namedStep(1, 1)],
                initialStepId: 1,
            }));
            const submit = button('reassignConfirm');
            expect(submit.disabled).toBe(true);

            await click(button('approvalAnyApprover'));
            expect(submit.disabled).toBe(false);
            const comment = document.querySelector<HTMLTextAreaElement>('#approval-management-comment');
            if (!comment) throw new Error('Comment field not found');
            await act(async () => {
                const setValue = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
                setValue?.call(comment, 'x'.repeat(501));
                comment.dispatchEvent(new Event('input', { bubbles: true }));
            });

            expect(submit.disabled).toBe(true);
        });

        it('caps the member picker at 20 selections in the rendered UI', async () => {
            await render(createElement(DialogHarness, {
                steps: [namedStep(1, 1)],
                initialStepId: 1,
                members: Array.from({ length: 21 }, (_, index) => member(index + 1)),
            }));

            for (let id = 1; id <= 20; id += 1) await chooseMember(id);

            expect(document.querySelectorAll('[data-slot="combobox-chip"]')).toHaveLength(20);
            expect(document.querySelector('[data-slot="combobox-item"][data-disabled]')?.textContent)
                .toContain('Member 21');
            expect(document.body.textContent).toContain('approvalMembersLimit');
        });

        it('keeps confirmation disabled and explains a named-approver quorum shortfall', async () => {
            const quorumStep = {
                ...namedStep(1, 1, [1, 2]),
                requiredCount: 3,
                approvedCount: 1,
                decisions: [{
                    id: 1,
                    stepId: 1,
                    decision: 'approved' as const,
                    decidedBy: 1,
                    decidedAt: '2026-09-02T10:30:00Z',
                }],
            };
            await render(createElement(DialogHarness, {
                steps: [quorumStep],
                initialStepId: 1,
                members: [member(1), member(2), member(3)],
            }));

            await chooseMember(2);
            expect(button('reassignConfirm').disabled).toBe(true);
            expect(document.body.textContent).toContain('approvalQuorumShortfall');

            await chooseMember(3);
            expect(button('reassignConfirm').disabled).toBe(false);
            expect(document.body.textContent).not.toContain('approvalQuorumShortfall');
        });

        it('requires the any-approver pool to contain enough undecided candidates', async () => {
            const quorumStep = {
                ...namedStep(1, 1, [1]),
                requiredCount: 3,
                approvedCount: 1,
                decisions: [{
                    id: 1,
                    stepId: 1,
                    decision: 'approved' as const,
                    decidedBy: 1,
                    decidedAt: '2026-09-02T10:30:00Z',
                }],
            };
            await render(createElement(DialogHarness, {
                steps: [quorumStep],
                initialStepId: 1,
                members: [member(1), member(2)],
            }));

            await click(button('approvalAnyApprover'));
            expect(button('reassignConfirm').disabled).toBe(true);
            expect(document.body.textContent).toContain('approvalQuorumShortfall');
        });

        it('renders as a scrollable bottom sheet on mobile', async () => {
            mobileViewport = true;
            await render(createElement(DialogHarness, {
                steps: [namedStep(1, 1)],
                initialStepId: 1,
            }));

            expect(document.querySelector('[data-slot="drawer-content"]')).not.toBeNull();
            expect(document.querySelector('[data-slot="dialog-content"]')).toBeNull();
            expect(document.querySelector('[data-slot="drawer-inner"] > .overflow-y-auto')).not.toBeNull();
        });

        it('provides labelled focus, keyboard selections, and guarded Escape and outside dismissal', async () => {
            const onClose = vi.fn();
            await render(createElement(DialogHarness, {
                steps: [namedStep(1, 1), namedStep(2, 2)],
                initialStepId: null,
                onClose,
            }));

            const stepPicker = button('approvalStepPlaceholder');
            expect(document.querySelector('label[for="approval-management-step"]')).not.toBeNull();
            expect(stepPicker).toBe(document.activeElement);
            await keyDown(stepPicker, 'ArrowDown');
            const focusedOption = document.activeElement;
            if (!(focusedOption instanceof HTMLElement)) throw new Error('Select option did not receive focus');
            await keyDown(focusedOption, 'Enter');
            expect(stepPicker.textContent).toContain('Finance');
            expect(document.body.textContent).toContain('approvalMembersLabel');
            expect(document.querySelector('label[for="approval-management-members"]')).not.toBeNull();
            expect(document.querySelector('label[for="approval-management-comment"]')).not.toBeNull();

            const memberPicker = document.querySelector<HTMLInputElement>('#approval-management-members');
            if (!memberPicker) throw new Error('Member picker input not found');
            memberPicker.focus();
            await keyDown(memberPicker, 'ArrowDown');
            await keyDown(memberPicker, 'Enter');
            expect(document.querySelector('[data-slot="combobox-chip"]')?.textContent).toContain('Member 1');

            const namedMembers = button('approvalNamedMembers');
            namedMembers.focus();
            await keyDown(namedMembers, 'ArrowRight');
            expect(button('approvalAnyApprover').getAttribute('aria-pressed')).toBe('true');
            await keyDown(document.body, 'Escape');
            expect(onClose).not.toHaveBeenCalled();
            expect([...document.querySelectorAll<HTMLElement>('[role="dialog"]')].some(
                (dialog) => dialog.textContent?.includes('ConfirmDiscard.title'),
            )).toBe(true);

            await click(button('ConfirmDiscard.keepEditing'));
            await act(async () => root.unmount());
            root = createRoot(container);
            await render(createElement(DialogHarness, {
                steps: [namedStep(1, 1)],
                initialStepId: 1,
                initialComment: 'draft',
                onClose,
            }));
            const approvalDialog = [...document.querySelectorAll<HTMLElement>('[role="dialog"]')].find(
                (dialog) => dialog.textContent?.includes('reassignDialogTitle'),
            );
            const overlay = approvalDialog?.previousElementSibling;
            if (!(overlay instanceof HTMLElement) || overlay.dataset.slot !== 'dialog-overlay') {
                throw new Error('Approval dialog overlay not found');
            }
            await pointerClick(overlay);
            expect(onClose).not.toHaveBeenCalled();
            expect([...document.querySelectorAll<HTMLElement>('[role="dialog"]')].some(
                (dialog) => dialog.textContent?.includes('ConfirmDiscard.title'),
            )).toBe(true);
            await click(button('ConfirmDiscard.discard'));
            expect(onClose).toHaveBeenCalledOnce();
        });
    });

    async function renderDocuments(options: {
        canManageApprovals: boolean;
        canApprove?: boolean;
        document?: DealDocument;
    }) {
        await render(createElement(DealDocuments, {
            dealId: 10,
            initial: [options.document ?? pendingDocument([namedStep(1, 1)])],
            canApprove: options.canApprove ?? true,
            canManageApprovals: options.canManageApprovals,
            canDeleteDocuments: false,
            currentUserId: 1,
        }));
    }

    describe('DealDocuments approval-step management', () => {
        it('renders management actions only for managers and makes the manager mutation reachable', async () => {
            await renderDocuments({ canManageApprovals: false });
            await openActionsMenu();
            expect(document.body.textContent).not.toContain('widenApprovers');
            expect(document.body.textContent).not.toContain('reassignApprovers');
            expect(api.getActiveWorkspaceMembers).not.toHaveBeenCalled();

            await act(async () => root.unmount());
            root = createRoot(container);
            const updatedApproval = approval([namedStep(1, 1, [1, 2])]);
            api.widenDocumentApprovalStepApprovers.mockResolvedValue(updatedApproval);
            api.getDealDocumentById.mockResolvedValue(pendingDocument(updatedApproval.steps));
            await renderDocuments({ canManageApprovals: true });
            await openActionsMenu();
            expect(document.body.textContent).toContain('widenApprovers');
            expect(document.body.textContent).toContain('reassignApprovers');

            await pointerClick(menuItem('widenApprovers'));
            await chooseMember(2);
            await click(button('widenConfirm'));

            expect(api.widenDocumentApprovalStepApprovers).toHaveBeenCalledWith(
                10,
                20,
                1,
                { approvers: [{ approverKind: 'user', userId: 2 }], comment: null },
            );
        });

        it('opens a parallel approval with no selected step and cannot submit before a choice', async () => {
            await renderDocuments({
                canManageApprovals: true,
                document: pendingDocument([namedStep(1, 1), namedStep(2, 2)], 'parallel'),
            });

            await chooseMenuAction('reassignApprovers');

            expect(button('approvalStepPlaceholder')).toBe(document.activeElement);
            expect(button('reassignConfirm').disabled).toBe(true);
            expect(document.querySelector('[data-slot="combobox-item"]')).toBeNull();
        });

        it('uses only server candidates even when the historical directory is unavailable', async () => {
            api.getActiveWorkspaceMembers.mockRejectedValue(new Error('directory unavailable'));
            api.getDocumentApprovalApproverCandidates.mockResolvedValue(candidates([member(23)]));
            await renderDocuments({ canManageApprovals: true });
            expect(api.getDocumentApprovalApproverCandidates).not.toHaveBeenCalled();

            await chooseMenuAction('reassignApprovers');
            const input = document.querySelector<HTMLInputElement>('#approval-management-members');
            if (!input) throw new Error('Member picker input not found');
            await pointerClick(input);
            await keyDown(input, 'ArrowDown');

            expect(api.getDocumentApprovalApproverCandidates).toHaveBeenCalledWith(
                10, 20, 1, { signal: expect.any(AbortSignal) },
            );
            const options = [...document.querySelectorAll<HTMLElement>('[data-slot="combobox-item"]')]
                .map((candidate) => candidate.textContent);
            expect(options).toEqual([expect.stringContaining('Member 23')]);
        });

        it('shows loading, failure and retry independently of historical labels', async () => {
            const pending = deferred<ApprovalStepCandidates>();
            api.getDocumentApprovalApproverCandidates.mockReturnValueOnce(pending.promise);
            await renderDocuments({ canManageApprovals: true });
            await chooseMenuAction('reassignApprovers');
            const input = document.querySelector<HTMLInputElement>('#approval-management-members');
            expect(input?.placeholder).toBe('approvalCandidatesLoading');
            expect(button('reassignConfirm').disabled).toBe(true);

            await act(async () => pending.reject(new Error('unavailable')));
            expect(document.body.textContent).toContain('approvalCandidatesUnavailable');
            api.getDocumentApprovalApproverCandidates.mockResolvedValue(candidates([member(2)]));
            await click(button('approvalMembersRetry'));
            await chooseMember(2);
            expect(button('reassignConfirm').disabled).toBe(false);
        });

        it('shows an empty server result and keeps confirmation disabled', async () => {
            api.getDocumentApprovalApproverCandidates.mockResolvedValue(candidates([]));
            await renderDocuments({ canManageApprovals: true });
            await chooseMenuAction('reassignApprovers');
            await click(button('approvalAnyApprover'));
            expect(document.body.textContent).toContain('approvalCandidatesEmpty');
            expect(button('reassignConfirm').disabled).toBe(true);
        });

        it('uses the server quorum context even when the document projection is older', async () => {
            api.getDocumentApprovalApproverCandidates.mockResolvedValue(candidates([member(2)], {
                requiredCount: 3, approvedCount: 2, remainingRequiredCount: 1,
            }));
            await renderDocuments({ canManageApprovals: true,
                document: pendingDocument([{ ...namedStep(1, 1), requiredCount: 4 }]),
            });
            await chooseMenuAction('reassignApprovers');
            await chooseMember(2);
            expect(document.body.textContent).toContain('approvalQuorumContext');
            expect(button('reassignConfirm').disabled).toBe(false);
        });

        it('does not count a newly decided member from the older document projection', async () => {
            api.getDocumentApprovalApproverCandidates.mockResolvedValue(candidates([member(1), member(2), member(3)], {
                requiredCount: 2, approvedCount: 1, remainingRequiredCount: 1,
                decidedByUserIds: [2], effectiveApproverIds: [2, 3],
            }));
            await renderDocuments({ canManageApprovals: true,
                document: pendingDocument([{ ...namedStep(1, 1, [2, 3]), requiredCount: 2 }]),
            });
            await chooseMenuAction('reassignApprovers');
            await chooseMember(2);
            expect(button('reassignConfirm').disabled).toBe(true);
            await chooseMember(3);
            expect(button('reassignConfirm').disabled).toBe(false);
        });

        it('counts newly effective approvers missing from the older document projection', async () => {
            api.getDocumentApprovalApproverCandidates.mockResolvedValue(candidates([member(2), member(3)], {
                requiredCount: 2, approvedCount: 0, remainingRequiredCount: 2,
                decidedByUserIds: [], effectiveApproverIds: [3],
            }));
            await renderDocuments({ canManageApprovals: true,
                document: pendingDocument([{ ...namedStep(1, 1, []), requiredCount: 2 }]),
            });
            await chooseMenuAction('widenApprovers');
            await chooseMember(2);
            expect(button('widenConfirm').disabled).toBe(false);
        });

        it('reports a filtered search miss separately from an empty candidate pool', async () => {
            api.getDocumentApprovalApproverCandidates.mockResolvedValue(candidates([member(2)]));
            await renderDocuments({ canManageApprovals: true });
            await chooseMenuAction('reassignApprovers');
            const input = document.querySelector<HTMLInputElement>('#approval-management-members');
            if (!input) throw new Error('Member picker input not found');
            await pointerClick(input);
            await keyDown(input, 'ArrowDown');
            await act(async () => {
                const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
                setter?.call(input, 'No such member');
                input.dispatchEvent(new Event('input', { bubbles: true }));
            });
            expect(document.body.textContent).toContain('approvalCandidatesNoMatches');
            expect(document.body.textContent).not.toContain('approvalCandidatesEmpty');
        });

        it('aborts superseded step requests and rejects an out-of-order response', async () => {
            const first = deferred<ApprovalStepCandidates>();
            const second = deferred<ApprovalStepCandidates>();
            api.getDocumentApprovalApproverCandidates.mockReturnValueOnce(first.promise)
                .mockReturnValueOnce(second.promise);
            await renderDocuments({ canManageApprovals: true,
                document: pendingDocument([namedStep(1, 1), namedStep(2, 2)], 'parallel') });
            await chooseMenuAction('reassignApprovers');
            expect(api.getDocumentApprovalApproverCandidates).not.toHaveBeenCalled();
            await chooseStep('Finance');
            const signal = api.getDocumentApprovalApproverCandidates.mock.calls[0][3]?.signal;
            await chooseStep('Legal', 'Finance');
            expect(signal?.aborted).toBe(true);
            await act(async () => second.resolve(candidates([member(3)])));
            await act(async () => first.resolve(candidates([member(2)])));
            await chooseMember(3);
            expect(document.body.textContent).not.toContain('Member 2');
        });

        it('does not revive a ready result when returning to a superseded step', async () => {
            const pendingB = deferred<ApprovalStepCandidates>();
            const refreshedA = deferred<ApprovalStepCandidates>();
            api.getDocumentApprovalApproverCandidates.mockResolvedValueOnce(candidates([member(2)]))
                .mockReturnValueOnce(pendingB.promise).mockReturnValueOnce(refreshedA.promise);
            await renderDocuments({ canManageApprovals: true,
                document: pendingDocument([namedStep(1, 1), namedStep(2, 2)], 'parallel') });
            await chooseMenuAction('reassignApprovers');
            await chooseStep('Finance');
            await click(button('approvalAnyApprover'));
            expect(button('reassignConfirm').disabled).toBe(false);
            await chooseStep('Legal', 'Finance');
            await chooseStep('Finance', 'Legal');
            expect(button('reassignConfirm').disabled).toBe(true);
            expect(document.body.textContent).toContain('approvalCandidatesLoading');
            await act(async () => pendingB.resolve(candidates([member(3)])));
            expect(button('reassignConfirm').disabled).toBe(true);
            await act(async () => refreshedA.resolve(candidates([])));
            expect(button('reassignConfirm').disabled).toBe(true);
            expect(document.body.textContent).toContain('approvalCandidatesEmpty');
        });

        it('aborts a request when the workspace changes and does not reuse its response', async () => {
            const first = deferred<ApprovalStepCandidates>();
            api.getDocumentApprovalApproverCandidates.mockReturnValueOnce(first.promise);
            await renderDocuments({ canManageApprovals: true });
            await chooseMenuAction('reassignApprovers');
            const signal = api.getDocumentApprovalApproverCandidates.mock.calls[0][3]?.signal;
            workspaceContext.id = 43;
            await renderDocuments({ canManageApprovals: true });
            expect(signal?.aborted).toBe(true);
            await act(async () => first.resolve(candidates([member(25)])));
            expect(document.querySelector('[role="dialog"]')).toBeNull();
            expect(document.body.textContent).not.toContain('Member 25');
        });

        it('drops a revoked selection when a refusal refreshes the candidates', async () => {
            api.getDocumentApprovalApproverCandidates.mockResolvedValueOnce(candidates([member(2), member(3)]))
                .mockResolvedValue(candidates([member(3)]));
            api.reassignDocumentApprovalStepApprovers.mockRejectedValue(Object.assign(new Error('revoked'), { status: 403 }));
            await renderDocuments({ canManageApprovals: true });
            await chooseMenuAction('reassignApprovers');
            await chooseMember(2);
            await click(button('reassignConfirm'));
            expect(api.getDocumentApprovalApproverCandidates).toHaveBeenCalledTimes(2);
            expect(document.body.textContent).not.toContain('Member 2');
            expect(button('reassignConfirm').disabled).toBe(true);
        });

        it('keeps reassignment but omits widening when every approver is already eligible', async () => {
            const openStep = { ...namedStep(1, 1), effectiveAnyApprover: true };
            await renderDocuments({
                canManageApprovals: true,
                document: pendingDocument([openStep]),
            });

            await openActionsMenu();
            expect(document.body.textContent).not.toContain('widenApprovers');
            expect(document.body.textContent).toContain('reassignApprovers');
        });

        it('applies the returned approval before reconciliation removes the current user affordances', async () => {
            let rejectRefresh!: (reason: unknown) => void;
            const refresh = new Promise<DealDocument>((_resolve, reject) => { rejectRefresh = reject; });
            const reassigned = approval([namedStep(1, 1, [2])]);
            api.reassignDocumentApprovalStepApprovers.mockResolvedValue(reassigned);
            api.getDealDocumentById.mockReturnValue(refresh);
            await renderDocuments({ canManageApprovals: true });
            await openActionsMenu();
            expect(document.body.textContent).toContain('approve');
            expect(document.body.textContent).toContain('delegate');

            await pointerClick(menuItem('reassignApprovers'));
            await chooseMember(2);
            await click(button('reassignConfirm'));

            expect(api.getDealDocumentById).toHaveBeenCalledOnce();
            expect(container.textContent).not.toContain('approveConfirm');
            expect(container.textContent).not.toContain('delegate');
            expect(container.textContent).not.toContain('approve');
            expect(toasts.success).toHaveBeenCalledWith('approversReassigned');

            await act(async () => {
                rejectRefresh(Object.assign(new Error('refresh failed'), { status: 500 }));
                await Promise.resolve();
            });
            expect(errors.show).toHaveBeenCalledWith(expect.any(Error), 'refreshFailed');
            expect(container.textContent).not.toContain('approve');
        });

        it.each([400, 404])(
            'refreshes both projections after a %s reassignment refusal',
            async (status) => {
                const requestError = Object.assign(new Error(`request failed: ${status}`), { status });
                api.reassignDocumentApprovalStepApprovers.mockRejectedValue(requestError);
                await renderDocuments({ canManageApprovals: true });
                await chooseMenuAction('reassignApprovers');
                await chooseMember(2);

                await click(button('reassignConfirm'));

                expect(errors.show).toHaveBeenCalledWith(requestError, 'reassignFailed');
                expect(api.getDealDocumentById).toHaveBeenCalledWith(10, 20);
                expect(api.getDocumentApprovalApproverCandidates).toHaveBeenCalledTimes(2);
            },
        );

        it('surfaces a 403 approver refusal through the API error toast with dedicated copy', async () => {
            const requestError = Object.assign(new Error('request failed: 403'), { status: 403 });
            api.reassignDocumentApprovalStepApprovers.mockRejectedValue(requestError);
            await renderDocuments({ canManageApprovals: true });
            await chooseMenuAction('reassignApprovers');
            await chooseMember(2);

            await click(button('reassignConfirm'));

            expect(errors.show).toHaveBeenCalledWith(
                requestError,
                'approverChangeRefused',
                'approverChangeRefusedDescription',
            );
            expect(dealsMessages.DealsDocuments.approverChangeRefused).toContain('refused');
            expect(japaneseDealsMessages.DealsDocuments.approverChangeRefused).toContain('拒否');
            expect(api.getDealDocumentById).toHaveBeenCalledWith(10, 20);
            expect(api.getDocumentApprovalApproverCandidates).toHaveBeenCalledTimes(2);
        });
    });
});
