/** @vitest-environment jsdom */
import { Editor } from '@tiptap/core';
import { EditorContent } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DocumentEditorToolbar } from '@/app/components/library/documents/editor/DocumentEditorToolbar';
import { MergeToken } from '@/app/components/library/documents/editor/MergeToken';
import messages from '@/messages/en/document-templates.json';

let editor: Editor | null = null;
afterEach(() => {
    cleanup();
    editor?.destroy();
    editor = null;
    vi.unstubAllGlobals();
});

describe('document merge-field menu focus', () => {
    it.each(['select', 'escape'])('returns to the saved editor caret after %s so typing continues there', async (action) => {
        vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => setTimeout(() => callback(0), 0));
        vi.stubGlobal('cancelAnimationFrame', clearTimeout);
        HTMLElement.prototype.scrollIntoView = vi.fn();
        Object.defineProperty(Range.prototype, 'getClientRects', { configurable: true, value: () => [] });
        Object.defineProperty(Range.prototype, 'getBoundingClientRect', { configurable: true, value: () => new DOMRect() });
        const instance = new Editor({
            extensions: [StarterKit, MergeToken],
            content: '<p>Prepared for  today.</p>',
        });
        editor = instance;
        render(<NextIntlClientProvider locale="en" messages={messages}>
            <DocumentEditorToolbar editor={instance} />
            <EditorContent editor={instance} />
        </NextIntlClientProvider>);
        act(() => { instance.commands.setTextSelection(14); instance.commands.focus(); });
        const trigger = screen.getByRole('button', { name: messages.DocumentTemplateBuilder.insertField });
        act(() => trigger.focus());
        fireEvent.keyDown(trigger, { key: 'ArrowDown' });
        const item = await screen.findByRole('menuitem', { name: messages.DocumentTemplateBuilder.fieldCompanyName });
        if (action === 'select') fireEvent.click(item);
        else fireEvent.keyDown(item, { key: 'Escape' });
        await waitFor(() => expect(document.activeElement).toBe(instance.view.dom));
        expect(instance.state.selection.from).toBe(action === 'select' ? 16 : 14);
        act(() => { instance.commands.insertContent('by our team'); });
        expect(instance.getText()).toContain('by our team');
        expect(instance.getJSON().content?.[0].content?.filter((node) => node.type === 'mergeToken')).toHaveLength(action === 'select' ? 1 : 0);
        expect(screen.queryByRole('menu')).toBeNull();
    });
});
