/** @vitest-environment jsdom */
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import ApprovalPolicyDialog from '@/app/components/records/approval-policies/ApprovalPolicyDialog';
import ApprovalPoliciesBrowser from '@/app/components/records/approval-policies/ApprovalPoliciesBrowser';
import { getActiveWorkspaceMembers, previewApprovalPolicyImpact, updateApprovalPolicy } from '@/app/lib/api';
import type { ApprovalPolicy } from '@/app/lib/types';
import en from '@/messages/en/approval-policies.json';
import ja from '@/messages/ja/approval-policies.json';
import common from '@/messages/en/common.json';
import { importRowErrorMessage } from '@/app/lib/import';
import enImport from '@/messages/en/importExport.json';
import jaImport from '@/messages/ja/importExport.json';
import jaCommon from '@/messages/ja/common.json';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/app/lib/api', () => ({
    createApprovalPolicy: vi.fn(), updateApprovalPolicy: vi.fn(), previewApprovalPolicyImpact: vi.fn(),
    getActiveWorkspaceMembers: vi.fn(), deleteApprovalPolicy: vi.fn(),
}));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock('@/app/lib/toast', () => ({ toastSuccess: vi.fn() }));
vi.mock('@/app/lib/renderedWorkspace', () => ({
    renderedWorkspaceId: () => 7, workspacePinnedRequest: () => ({ headers: { 'X-Workspace-Id': '7' } }),
}));
vi.mock('@/app/components/motion/Rise', () => ({ default: ({ children }: { children: ReactNode }) => <>{children}</> }));
vi.mock('@/app/components/records/approval-policies/ApprovalChainEditor', () => ({ default: () => null }));

const policy: ApprovalPolicy = {
    id: 1, name: 'Large quotes', active: true, currency: 'YEN', currencyValid: false,
    minTotal: 1000, mode: 'sequential', separationOfDuties: 'strict', steps: [], createdAt: '', updatedAt: '',
};

beforeEach(() => {
    Element.prototype.scrollIntoView = vi.fn();
    vi.mocked(getActiveWorkspaceMembers).mockResolvedValue([]);
    vi.mocked(previewApprovalPolicyImpact).mockResolvedValue({
        changeClass: 'RETARGET', effect: 'frozen_approvals_unchanged', pendingApprovalCount: 0, affected: [], impactFingerprint: 'currency-review',
    });
    vi.mocked(updateApprovalPolicy).mockResolvedValue({ ...policy, currency: 'JPY', currencyValid: true });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

function localized(child: ReactNode, locale: 'en' | 'ja' = 'en') {
    return <NextIntlClientProvider locale={locale} messages={locale === 'ja' ? { ...ja, ...jaCommon } : { ...en, ...common }}>
        {child}
    </NextIntlClientProvider>;
}

async function chooseCurrency(currency: string) {
    const trigger = document.getElementById('policy-currency');
    if (!trigger) throw new Error('Currency picker missing');
    fireEvent.keyDown(trigger, { key: 'Enter' });
    fireEvent.click(await screen.findByRole('option', { name: currency }));
}

describe('import currency refusal', () => {
    it.each([enImport, jaImport])('localizes the stable row refusal without replacing other import details', (messages) => {
        expect(importRowErrorMessage('CURRENCY_INVALID', messages.importExport.currencyInvalid))
            .toBe(messages.importExport.currencyInvalid);
        expect(importRowErrorMessage('Other row detail', messages.importExport.currencyInvalid)).toBe('Other row detail');
    });
});

describe('approval currency correction', () => {
    it.each(['en', 'ja'] as const)('warns about saved invalid currency and saves only an explicit correction in %s', async (locale) => {
        const messages = (locale === 'ja' ? ja : en).ApprovalPolicyDialog;
        const saved = vi.fn();
        render(localized(<ApprovalPolicyDialog open policy={policy} onOpenChange={vi.fn()} onSaved={saved} />, locale));
        expect(screen.getByRole('alert').textContent).toContain('YEN');
        const save = screen.getByRole('button', { name: messages.save });
        expect(save.hasAttribute('disabled')).toBe(true);
        await chooseCurrency('JPY');
        expect(screen.queryByRole('alert')).toBeNull();
        fireEvent.click(save);
        await waitFor(() => expect(updateApprovalPolicy).toHaveBeenCalledWith(1,
            expect.objectContaining({ currency: 'JPY' }), expect.anything()));
        expect(saved).toHaveBeenCalled();
    });

    it.each(['en', 'ja'] as const)('shows invalid policies to administrators with an edit path in %s', (locale) => {
        const messages = (locale === 'ja' ? ja : en).ApprovalPoliciesBrowser;
        render(localized(<ApprovalPoliciesBrowser policies={[policy]} />, locale));
        expect(screen.getByText(messages.invalidCurrencyActive)).toBeTruthy();
        expect(screen.getByText(messages.invalidCurrencyCondition)).toBeTruthy();
        fireEvent.click(screen.getByText(policy.name));
        expect(screen.getByRole('alert').textContent).toContain('YEN');
    });

    it('requires a currency for total thresholds but allows a currency-free discount policy', () => {
        const { rerender } = render(localized(<ApprovalPolicyDialog open policy={{ ...policy, currency: null }} onOpenChange={vi.fn()} onSaved={vi.fn()} />));
        expect(screen.getByRole('button', { name: en.ApprovalPolicyDialog.save }).hasAttribute('disabled')).toBe(true);
        rerender(localized(<ApprovalPolicyDialog open policy={{ ...policy, id: 2, currency: null, minTotal: null, minDiscountPercent: 10 }} onOpenChange={vi.fn()} onSaved={vi.fn()} />));
        expect(screen.getByRole('button', { name: en.ApprovalPolicyDialog.save }).hasAttribute('disabled')).toBe(false);
    });

    it('retains a valid saved currency outside the picker shortlist', () => {
        render(localized(<ApprovalPolicyDialog open policy={{ ...policy, currency: 'KWD', currencyValid: true }} onOpenChange={vi.fn()} onSaved={vi.fn()} />));
        expect(screen.queryByRole('alert')).toBeNull();
        expect(document.getElementById('policy-currency')?.textContent).toContain('KWD');
        expect(screen.getByRole('button', { name: en.ApprovalPolicyDialog.save }).hasAttribute('disabled')).toBe(false);
    });
});
