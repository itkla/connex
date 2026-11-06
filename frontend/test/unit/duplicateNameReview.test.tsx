/** @vitest-environment jsdom */
import type { PropsWithChildren } from 'react';
import { act, cleanup, render, renderHook, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DuplicatePreflightWarning from '@/app/components/records/DuplicatePreflightWarning';
import { duplicatePreflightResponseSignature, useDuplicatePreflight } from '@/app/hooks/useDuplicatePreflight';
import type { DuplicatePreflightDecision } from '@/app/hooks/useDuplicatePreflight';
import type { DuplicatePreflightResponse } from '@/app/lib/types';
import en from '@/messages/en/common.json';
import ja from '@/messages/ja/common.json';

const api = vi.hoisted(() => ({ preflight: vi.fn<typeof import('@/app/lib/api').preflightPersonDuplicates>() }));
vi.mock('@/app/lib/api', async (original) => ({
    ...await original<typeof import('@/app/lib/api')>(),
    preflightPersonDuplicates: api.preflight,
    preflightCompanyDuplicates: api.preflight,
    preflightDealDuplicates: api.preflight,
}));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 7 }) }));

function boundedReview(count = 1): DuplicatePreflightResponse {
    return {
        recordType: 'person', truncated: true, allowSeparateCreation: true, reviewToken: 'reviewed-name-bound',
        candidates: Array.from({ length: count }, (_, index) => ({
            recordId: index + 1, recordType: 'person', name: 'Jordan Lee',
            ownedByActiveWorkspace: true, strength: 'WEAK',
            matches: [{ kind: 'NAME', normalizedValue: 'jordan lee', strength: 'WEAK' }],
        })),
    };
}

beforeEach(() => api.preflight.mockReset());
afterEach(cleanup);

function English({ children }: PropsWithChildren) {
    return <NextIntlClientProvider locale="en" messages={en}>{children}</NextIntlClientProvider>;
}

describe('bounded person-name review', () => {
    it.each([0, 1, 50])('requires acknowledgement and submits the same proof with %i displayed names', async (count) => {
        const response = boundedReview(count);
        api.preflight.mockResolvedValue(response);
        const { result } = renderHook(() => useDuplicatePreflight('person', { name: 'Jordan Lee', email: 'different@example.com' }));
        await waitFor(() => expect(result.current.status).toBe('ready'));
        expect(result.current.blocked).toBe(true);
        let decision: DuplicatePreflightDecision | undefined;
        await act(async () => { decision = await result.current.reviewNow(); });
        expect(decision?.allowed).toBe(false);
        act(() => result.current.setAcknowledged(true));
        expect(result.current.blocked).toBe(false);
        await act(async () => { decision = await result.current.reviewNow(); });
        expect(decision?.allowed).toBe(true);
        expect(decision?.duplicateReviewToken).toBe(response.reviewToken);
    });

    it.each(['person', 'company', 'deal'] as const)('keeps ordinary truncated %s reviews blocked', async (kind) => {
        api.preflight.mockResolvedValue({ ...boundedReview(), recordType: kind, allowSeparateCreation: kind !== 'person' });
        const { result } = renderHook(() => useDuplicatePreflight(kind, { name: 'Jordan Lee' }));
        await waitFor(() => expect(result.current.status).toBe('ready'));
        act(() => result.current.setAcknowledged(true));
        expect(result.current.blocked).toBe(true);
        let decision: DuplicatePreflightDecision | undefined;
        await act(async () => { decision = await result.current.reviewNow(); });
        expect(decision?.allowed).toBe(false);
        expect(decision?.duplicateReviewToken).toBeNull();
    });

    it('invalidates acknowledgement when the permission or displayed review changes', async () => {
        const response = boundedReview();
        api.preflight.mockResolvedValue(response);
        const { result } = renderHook(() => useDuplicatePreflight('person', { name: 'Jordan Lee' }));
        await waitFor(() => expect(result.current.status).toBe('ready'));
        act(() => result.current.setAcknowledged(true));
        expect(result.current.blocked).toBe(false);
        const changed = { ...response, allowSeparateCreation: false };
        expect(duplicatePreflightResponseSignature(changed)).not.toBe(duplicatePreflightResponseSignature(response));
        api.preflight.mockResolvedValue(changed);
        let decision: DuplicatePreflightDecision | undefined;
        await act(async () => { decision = await result.current.reviewNow(); });
        expect(decision?.allowed).toBe(false);
        expect(result.current.acknowledged).toBe(false);
    });

    it.each([{ locale: 'en', messages: en }, { locale: 'ja', messages: ja }])('explains the limited name review and offers an explicit choice in $locale', ({ locale, messages }) => {
        render(<NextIntlClientProvider locale={locale} messages={messages}>
            <DuplicatePreflightWarning kind="person" status="ready" response={boundedReview(0)} onAcknowledgedChange={vi.fn()} />
        </NextIntlClientProvider>);
        expect(screen.getByText(messages.DuplicateWarning.nameOnlyTruncated)).toBeTruthy();
        expect(screen.getByRole('checkbox', { name: messages.DuplicateWarning.acknowledge })).toBeTruthy();
        expect(screen.queryByText(messages.DuplicateWarning.truncated)).toBeNull();
    });

    it('keeps the acknowledgement hidden for strong-identity overflow', () => {
        render(<English><DuplicatePreflightWarning kind="person" status="ready"
            response={{ ...boundedReview(), allowSeparateCreation: false }} onAcknowledgedChange={vi.fn()} /></English>);
        expect(screen.queryByRole('checkbox')).toBeNull();
        expect(screen.getByText(en.DuplicateWarning.truncated)).toBeTruthy();
    });

    it.each([{ locale: 'en', messages: en }, { locale: 'ja', messages: ja }])('labels company names as review evidence in $locale', ({ locale, messages }) => {
        const response = boundedReview();
        render(<NextIntlClientProvider locale={locale} messages={messages}>
            <DuplicatePreflightWarning kind="company" status="ready" response={{
                ...response, recordType: 'company', truncated: false, allowSeparateCreation: false,
                candidates: response.candidates.map((candidate) => ({ ...candidate, recordType: 'company', name: 'Sakura Robotics Inc.' })),
            }} onAcknowledgedChange={vi.fn()} />
        </NextIntlClientProvider>);
        expect(screen.getByText(messages.DuplicateWarning.companyNameStrength)).toBeTruthy();
        expect(screen.getByText(messages.DuplicateWarning.companyNameEvidence)).toBeTruthy();
        expect(screen.getByRole('checkbox')).toBeTruthy();
    });
});
