/** @vitest-environment jsdom */
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Editor } from '@tiptap/core';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import TemplateBuilder from '@/app/components/library/documents/TemplateBuilder';
import DocumentBodyEditor from '@/app/components/library/documents/editor/DocumentBodyEditor';
import type { DocumentTemplate } from '@/app/lib/types';
import common from '@/messages/en/common.json';
import documents from '@/messages/en/document-templates.json';

const router = vi.hoisted(() => ({ push: vi.fn(), refresh: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => router }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock('@/app/lib/api', () => ({ createDocumentTemplate: vi.fn(), updateDocumentTemplate: vi.fn() }));

beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
    vi.stubGlobal('matchMedia', (media: string) => ({ media, matches: false, addEventListener() {}, removeEventListener() {} }));
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function localized(children: React.ReactNode) {
    return <NextIntlClientProvider locale="en" messages={{ ...common, ...documents }} timeZone="UTC">{children}</NextIntlClientProvider>;
}

async function editor() {
    return waitFor(() => {
        const element = document.querySelector('.tiptap');
        if (!element || !('editor' in element) || !(element.editor instanceof Editor)) throw new Error('Tiptap has not mounted');
        return element.editor;
    });
}

function unloadPrevented() {
    const event = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(event);
    return event.defaultPrevented;
}

const template: DocumentTemplate = { id: 17, name: 'Quote', type: 'quote', locale: 'en', active: true, createdAt: '', updatedAt: '' };

describe('document-template initialization with the installed Tiptap editor', () => {
    it.each([null, { ...template, body: null }, { ...template, body: '{"type":"doc","content":[{"type":"paragraph"}]}' }, { ...template, intro: 'Existing introduction' }])(
        'leaves an untouched template clean after editor initialization (%j)', async (initialTemplate) => {
            render(localized(<TemplateBuilder template={initialTemplate} />));
            await editor();
            expect(unloadPrevented()).toBe(false);
            fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
            expect(router.push).toHaveBeenCalledExactlyOnceWith('/library/documents');
            expect(screen.queryByRole('dialog')).toBeNull();
        },
    );

    it('does not publish an edit when changing editability or loading incoming content', async () => {
        const onChange = vi.fn<(value: string | null) => void>();
        const { rerender } = render(localized(<DocumentBodyEditor value={null} onChange={onChange} />));
        await editor();
        rerender(localized(<DocumentBodyEditor value={null} onChange={onChange} editable={false} />));
        rerender(localized(<DocumentBodyEditor value={'{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Loaded"}]}]}'} onChange={onChange} />));
        expect(onChange).not.toHaveBeenCalled();
    });

    it('keeps a legacy body clean when Tiptap adds normalized heading and paragraph attributes', async () => {
        const body = JSON.stringify({ type: 'doc', content: [
            { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: 'Legacy heading' }] },
            { type: 'paragraph', content: [{ type: 'text', text: 'Legacy paragraph' }] },
        ] });
        render(localized(<TemplateBuilder template={{ ...template, body }} />));
        const instance = await editor();
        const normalized = instance.getJSON();
        expect(normalized.content?.[0]?.attrs).toHaveProperty('textAlign');
        expect(normalized.content?.[1]?.attrs).toHaveProperty('textAlign');
        expect(JSON.stringify(normalized)).not.toBe(body);
        expect(instance.getText()).toContain('Legacy paragraph');
        expect(unloadPrevented()).toBe(false);
        fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
        expect(router.push).toHaveBeenCalledExactlyOnceWith('/library/documents');
        expect(screen.queryByRole('dialog')).toBeNull();
    });

    it.each([null, { ...template, body: '{"type":"doc","content":[{"type":"paragraph"}]}' }])('normalizes a reverted empty body to the initial clean state (%j)', async (initialTemplate) => {
        render(localized(<TemplateBuilder template={initialTemplate} />));
        const instance = await editor();
        await act(async () => { instance.commands.setContent('<p>Actual user content</p>'); });
        expect(unloadPrevented()).toBe(true);
        await act(async () => { instance.commands.clearContent(); });
        expect(unloadPrevented()).toBe(false);
        fireEvent.click(screen.getByRole('button', { name: 'Documents' }));
        expect(router.push).toHaveBeenCalledExactlyOnceWith('/library/documents');
    });
});
