/** @vitest-environment jsdom */

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { RecordListState } from '@/app/components/records/RecordListState';
import { ApiError } from '@/app/lib/api';
import * as sessionExpiry from '@/app/lib/sessionExpiry';
import en from '@/messages/en/records.json';
import ja from '@/messages/ja/records.json';

beforeEach(() => {
    vi.spyOn(sessionExpiry, 'redirectToSignIn').mockReturnValue(true);
});
afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe.each([{ locale: 'en', messages: en }, { locale: 'ja', messages: ja }])('record list recovery in $locale', ({ locale, messages }) => {
    const t = messages.RecordListState;

    it('replaces empty onboarding with a localized error, support reference and Retry', () => {
        const retry = vi.fn();
        render(
            <NextIntlClientProvider locale={locale} messages={messages}>
                <RecordListState error={new ApiError('private diagnostic', 503, undefined, undefined, 'test-reference')} loading={false} hasRecords={false} onRetry={retry}>
                    <p>No contacts yet</p>
                </RecordListState>
            </NextIntlClientProvider>,
        );

        expect(screen.queryByText('No contacts yet')).toBeNull();
        expect(screen.getByRole('alert').textContent).toContain(t.title);
        expect(screen.getByRole('alert').textContent).toContain(t.reference.replace('{id}', 'test-reference'));
        expect(screen.queryByText('private diagnostic')).toBeNull();
        fireEvent.click(screen.getByRole('button', { name: t.retry }));
        expect(retry).toHaveBeenCalledOnce();
    });

    it('keeps loaded rows and explains they are stale while a retry is busy', () => {
        render(
            <NextIntlClientProvider locale={locale} messages={messages}>
                <RecordListState error={new Error('offline')} loading hasRecords onRetry={vi.fn()}>
                    <p>Previously loaded contact</p>
                </RecordListState>
            </NextIntlClientProvider>,
        );

        expect(screen.getByText('Previously loaded contact')).toBeDefined();
        expect(screen.getByRole('alert').textContent).toContain(t.refreshBody);
        expect(screen.getByRole('button', { name: t.retrying }).hasAttribute('disabled')).toBe(true);
    });

    it('gives access guidance for permission refusals', () => {
        render(
            <NextIntlClientProvider locale={locale} messages={messages}>
                <RecordListState error={new ApiError('internal permission', 403)} loading={false} hasRecords={false} onRetry={vi.fn()}>
                    <p>No contacts yet</p>
                </RecordListState>
            </NextIntlClientProvider>,
        );

        expect(screen.getByRole('alert').textContent).toContain(t.deniedTitle);
        expect(screen.getByRole('alert').textContent).toContain(t.deniedBody);
        expect(screen.queryByText('No contacts yet')).toBeNull();
        expect(sessionExpiry.redirectToSignIn).not.toHaveBeenCalled();
    });

    it('hides retained rows after a permission refusal until a successful retry', () => {
        const content = (error: Error | null) => (
            <NextIntlClientProvider locale={locale} messages={messages}>
                <RecordListState error={error} loading={false} hasRecords onRetry={vi.fn()}>
                    <p>Previously loaded contact</p>
                </RecordListState>
            </NextIntlClientProvider>
        );
        const { rerender } = render(content(null));
        expect(screen.getByText('Previously loaded contact')).toBeDefined();

        rerender(content(new ApiError('internal permission', 403)));
        expect(screen.queryByText('Previously loaded contact')).toBeNull();
        expect(screen.getByRole('alert').textContent).toContain(t.deniedBody);
        expect(screen.getByRole('alert').textContent).not.toContain(t.refreshBody);
        expect(sessionExpiry.redirectToSignIn).not.toHaveBeenCalled();

        rerender(content(null));
        expect(screen.queryByRole('alert')).toBeNull();
        expect(screen.getByText('Previously loaded contact')).toBeDefined();
    });

    it.each([
        { status: 401, emptyBody: false },
        { status: 401, emptyBody: true },
        { status: 403, emptyBody: true },
    ])('redirects an expired session ($status, empty body: $emptyBody) without collection errors or retained rows', ({ status, emptyBody }) => {
        render(
            <NextIntlClientProvider locale={locale} messages={messages}>
                <RecordListState error={new ApiError('session expired', status, undefined, undefined, undefined, emptyBody)} loading={false} hasRecords onRetry={vi.fn()}>
                    <p>Previously loaded contact</p>
                </RecordListState>
            </NextIntlClientProvider>,
        );

        expect(sessionExpiry.redirectToSignIn).toHaveBeenCalledOnce();
        expect(screen.queryByRole('alert')).toBeNull();
        expect(screen.queryByRole('button')).toBeNull();
        expect(screen.queryByText('Previously loaded contact')).toBeNull();
    });

    it('allows the genuinely empty state after success', () => {
        render(
            <NextIntlClientProvider locale={locale} messages={messages}>
                <RecordListState error={null} loading={false} hasRecords={false} onRetry={vi.fn()}>
                    <p>No contacts yet</p>
                </RecordListState>
            </NextIntlClientProvider>,
        );

        expect(screen.queryByRole('alert')).toBeNull();
        expect(screen.getByText('No contacts yet')).toBeDefined();
    });
});
