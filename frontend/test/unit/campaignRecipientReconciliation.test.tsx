/** @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CampaignRecipientsDialog from '@/app/components/marketing/campaigns/CampaignRecipientsDialog';
import english from '@/messages/en/campaigns.json';
import japanese from '@/messages/ja/campaigns.json';

const api = vi.hoisted(() => ({
    getCampaignRecipients: vi.fn(),
    reconcileCampaignRecipient: vi.fn(),
    showApiError: vi.fn(),
    toastSuccess: vi.fn(),
    refresh: vi.fn(),
}));

vi.mock('@/app/lib/api', () => ({
    getCampaignRecipients: api.getCampaignRecipients,
    reconcileCampaignRecipient: api.reconcileCampaignRecipient,
}));
vi.mock('@/app/hooks/useApiErrorToast', () => ({
    useApiErrorToast: () => api.showApiError,
}));
vi.mock('@/app/lib/toast', () => ({ toastSuccess: api.toastSuccess }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: api.refresh }) }));

declare global {
    var IS_REACT_ACT_ENVIRONMENT: boolean;
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const messages = {
    CampaignRecipients: {
        title: '{counter}',
        descriptionFailed: '{count} failed',
        loading: 'Loading',
        emptyTitle: 'Empty',
        emptyBody: 'Empty body',
        failedTitle: 'Failed',
        failedBody: 'Failed body',
        contactUnnamed: 'Contact',
        contactRemoved: 'Removed',
        sendDetailsUnavailable: 'Send details unavailable',
        reconciliationRequired: 'Needs confirmation',
        reasonCodes: {
            deadline_ambiguous: 'Deadline result unknown',
        },
        markDelivered: 'Mark as delivered',
        markNotDelivered: 'Mark as not delivered',
        reconciliationPermissionRequired: 'Permission required',
        deliveredConfirmTitle: 'Confirm delivered',
        deliveredConfirmDescription: 'Delivered consequence',
        notDeliveredConfirmTitle: 'Confirm not delivered',
        notDeliveredConfirmDescription: 'Not delivered consequence',
        cancel: 'Cancel',
        confirmDelivered: 'Confirm delivered action',
        confirmNotDelivered: 'Confirm not delivered action',
        confirming: 'Updating',
        markedDelivered: 'Marked delivered',
        markedNotDelivered: 'Marked not delivered',
        reconcileFailed: 'Failed update',
        page: '{page}/{pageCount}',
        previousPage: 'Previous',
        nextPage: 'Next',
    },
};

let container: HTMLDivElement;
let root: Root;

beforeEach(async () => {
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
    vi.stubGlobal('ResizeObserver', class {
        observe() {}
        unobserve() {}
        disconnect() {}
    });
    api.getCampaignRecipients.mockResolvedValue({
        items: [{
            deliveryId: 13,
            sendId: 11,
            channel: 'email',
            status: 'failed',
            reconciliationRequired: true,
            reasonCode: 'deadline_ambiguous',
        }],
        total: 1,
    });
    api.reconcileCampaignRecipient.mockResolvedValue({
        deliveryId: 13,
        status: 'dispatched',
        reconciliationRequired: false,
        reasonCode: null,
    });
    await act(async () => {
        root.render(
            <NextIntlClientProvider locale="en" messages={messages}>
                <CampaignRecipientsDialog
                    campaignId={7}
                    counter="failed"
                    counterLabel="Failed"
                    canReconcile
                    open
                    onOpenChange={vi.fn()}
                />
            </NextIntlClientProvider>,
        );
        await Promise.resolve();
    });
});

afterEach(async () => {
    await act(async () => root.unmount());
    document.body.replaceChildren();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

function button(label: string): HTMLButtonElement {
    const found = [...document.querySelectorAll<HTMLButtonElement>('button')]
        .find((candidate) => candidate.textContent === label);
    if (!found) throw new Error(`No button found for ${label}`);
    return found;
}

async function click(target: HTMLButtonElement) {
    await act(async () => {
        target.click();
        await Promise.resolve();
    });
}

describe('campaign recipient delivery reconciliation', () => {
    it.each([
        { locale: 'en', catalog: english, summary: '2 messages sent', firstSend: 'Send 1 · Newsletter', secondSend: 'Send 2 · Newsletter' },
        { locale: 'ja', catalog: japanese, summary: '送信済みメッセージ2件', firstSend: '配信1 · Newsletter', secondSend: '配信2 · Newsletter' },
    ])('counts repeat sends and distinguishes their rows in $locale', async ({ locale, catalog, summary, firstSend, secondSend }) => {
        const recipient = {
            channel: 'email', personId: 17, personLabel: 'Repeated contact', status: 'dispatched',
            reconciliationRequired: false, reasonCode: null, messageName: 'Newsletter',
        };
        api.getCampaignRecipients.mockResolvedValue({
            items: [
                { ...recipient, deliveryId: 31, sendId: 11, sendNumber: 1 },
                { ...recipient, deliveryId: 32, sendId: 41, sendNumber: 2 },
            ],
            total: 2,
        });
        await act(async () => {
            root.render(
                <NextIntlClientProvider locale={locale} messages={catalog}>
                    <CampaignRecipientsDialog campaignId={7} counter="dispatched" counterLabel={catalog.CampaignEngagement.dispatched}
                        canReconcile={false} open onOpenChange={vi.fn()} />
                </NextIntlClientProvider>,
            );
            await Promise.resolve();
        });

        expect(document.body.textContent).toContain(summary);
        expect(document.body.textContent).not.toContain(locale === 'en' ? '2 contacts' : '連絡先2件');
        const rows = [...document.querySelectorAll('li')];
        expect(rows).toHaveLength(2);
        expect(rows[0]?.textContent).toContain(firstSend);
        expect(rows[1]?.textContent).toContain(secondSend);
        expect(rows.map((row) => row.querySelector('a')?.getAttribute('href')))
            .toEqual(['/records/contacts/17', '/records/contacts/17']);
        expect(api.getCampaignRecipients).toHaveBeenLastCalledWith(7, { submitted: true, page: 1, size: 25 });
    });

    it('disables both actions and explains the missing permission', async () => {
        await act(async () => {
            root.render(
                <NextIntlClientProvider locale="en" messages={messages}>
                    <CampaignRecipientsDialog
                        campaignId={7}
                        counter="failed"
                        counterLabel="Failed"
                        canReconcile={false}
                        open
                        onOpenChange={vi.fn()}
                    />
                </NextIntlClientProvider>,
            );
            await Promise.resolve();
        });

        expect(button('Mark as delivered').disabled).toBe(true);
        expect(button('Mark as not delivered').disabled).toBe(true);
        expect(document.body.textContent).toContain('Permission required');
        expect(api.reconcileCampaignRecipient).not.toHaveBeenCalled();
    });

    it('opens and cancels the confirmation without posting', async () => {
        await click(button('Mark as delivered'));

        expect(document.body.textContent).toContain('Delivered consequence');
        expect(api.reconcileCampaignRecipient).not.toHaveBeenCalled();

        await click(button('Cancel'));

        expect(document.body.textContent).not.toContain('Delivered consequence');
        expect(api.reconcileCampaignRecipient).not.toHaveBeenCalled();
        expect(api.refresh).not.toHaveBeenCalled();
    });

    it.each([
        ['Mark as delivered', 'Confirm delivered action', 'delivered'],
        ['Mark as not delivered', 'Confirm not delivered action', 'not_delivered'],
    ] as const)('confirms %s with one reconciliation POST', async (openLabel, confirmLabel, resolution) => {
        await click(button(openLabel));
        await click(button(confirmLabel));

        expect(api.reconcileCampaignRecipient).toHaveBeenCalledTimes(1);
        expect(api.reconcileCampaignRecipient).toHaveBeenCalledWith(7, 13, { resolution });
        expect(api.refresh).toHaveBeenCalledTimes(1);
    });

    it('keeps parent data unchanged when reconciliation fails', async () => {
        api.reconcileCampaignRecipient.mockRejectedValueOnce(new Error('failed'));

        await click(button('Mark as delivered'));
        await click(button('Confirm delivered action'));

        expect(api.showApiError).toHaveBeenCalledTimes(1);
        expect(api.refresh).not.toHaveBeenCalled();
    });
});
