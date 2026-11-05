/** @vitest-environment jsdom */
import { useState } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Combobox, ComboboxChips, ComboboxChipsInput, ComboboxContent, ComboboxEmpty, ComboboxInput, ComboboxItem, ComboboxList } from '@/components/ui/combobox';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { Drawer, DrawerContent, DrawerDescription, DrawerTitle } from '@/components/ui/drawer';
import ConfirmDiscardDialog from '@/app/components/ConfirmDiscardDialog';
import { useTimeZoneSearch } from '@/app/hooks/useTimeZoneSearch';
import { useUnsavedChangesGuard } from '@/app/hooks/useUnsavedChangesGuard';
import messages from '@/messages/en/common.json';
import jaMessages from '@/messages/ja/common.json';

const choices = ['Fuji Logistics', '北川商事', 'Kitagawa', 'ガーデン商事', ...Array.from({ length: 200 }, (_, i) => `Company ${i}`)];

beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class {
        observe() {}
        unobserve() {}
        disconnect() {}
    });
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => setTimeout(() => callback(0), 0));
    vi.stubGlobal('cancelAnimationFrame', clearTimeout);
    Object.defineProperty(window, 'matchMedia', { configurable: true, value: (media: string) => ({
        media, matches: false, addEventListener() {}, removeEventListener() {},
    }) });
    HTMLElement.prototype.scrollIntoView = vi.fn();
});

afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
});

function Picker({ chips = false, server = false, initialSelection, onValueChange = vi.fn() }: {
    chips?: boolean; server?: boolean; initialSelection?: string; onValueChange?: (value: string | null, details: unknown) => void;
}) {
    return <Combobox<string, boolean> items={choices} multiple={chips} defaultValue={initialSelection}
        onValueChange={(value, details) => onValueChange(Array.isArray(value) ? value.at(-1) ?? null : value, details)}
        filter={server ? null : undefined}>
        {chips ? <ComboboxChips><ComboboxChipsInput aria-label="Company" /></ComboboxChips> : <ComboboxInput aria-label="Company" />}
        <ComboboxContent>
            <ComboboxEmpty>No companies</ComboboxEmpty>
            <ComboboxList>{(item: string) => <ComboboxItem key={item} value={item}>{item}</ComboboxItem>}</ComboboxList>
        </ComboboxContent>
    </Combobox>;
}

const zones = ['Asia/Tokyo', 'America/Los_Angeles', 'UTC'];
function ZonePicker() {
    const search = useTimeZoneSearch(zones);
    return <Combobox items={zones} {...search}>
        <ComboboxInput aria-label="Time zone" />
        <ComboboxContent>
            <ComboboxEmpty>No zones</ComboboxEmpty>
            <ComboboxList>{(zone: string) => <ComboboxItem key={zone} value={zone}>{search.itemToStringLabel(zone)}</ComboboxItem>}</ComboboxList>
        </ComboboxContent>
    </Combobox>;
}

function Form({ chips = false, server = false, onSelect = vi.fn(), onSubmit = vi.fn() }) {
    return <form onSubmit={(event) => { event.preventDefault(); onSubmit(); }}>
        <Picker chips={chips} server={server} onValueChange={onSelect} />
        <button type="submit">Save</button>
    </form>;
}

function LayeredForm({ drawer = false }: { drawer?: boolean }) {
    const [open, setOpen] = useState(true);
    const [dirty, setDirty] = useState(false);
    const guard = useUnsavedChangesGuard({ isDirty: dirty, onClose: () => setOpen(false) });
    const body = <>
        <label>Draft<input onChange={() => setDirty(true)} /></label>
        <Picker />
    </>;
    const confirm = <ConfirmDiscardDialog {...guard.confirm} />;
    return <NextIntlClientProvider locale="en" messages={messages}>
        <button type="button">Outside the form</button>
        {drawer ? <Drawer open={open} onOpenChange={guard.onOpenChange}>
            <DrawerContent><DrawerTitle>Edit</DrawerTitle><DrawerDescription>Edit record</DrawerDescription>{body}</DrawerContent>
            {confirm}
        </Drawer> : <Dialog open={open} onOpenChange={guard.onOpenChange}>
            <DialogContent><DialogTitle>New contact</DialogTitle><DialogDescription>Create record</DialogDescription>{body}</DialogContent>
            {confirm}
        </Dialog>}
    </NextIntlClientProvider>;
}

describe('shared picker collection and keys', () => {
    it.each(['en', 'ja'])('filters friendly zone aliases through the real picker in %s', async (locale) => {
        render(<NextIntlClientProvider locale={locale} messages={messages}><ZonePicker /></NextIntlClientProvider>);
        const input = screen.getByRole('combobox');
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        fireEvent.change(input, { target: { value: 'JST' } });
        await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(1));
        expect(screen.getByRole('option').textContent).toContain('Asia/Tokyo');
        expect(screen.getByRole('option').textContent).toContain(locale === 'ja' ? '日本' : 'Japan');
    });
    it('caps rendered rows, searches all items, and only renders matching choices', async () => {
        render(<Form />);
        const input = screen.getByRole('combobox');
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(100));
        fireEvent.change(input, { target: { value: 'Company 199' } });
        await waitFor(() => expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual(['Company 199']));
        fireEvent.change(input, { target: { value: '北川' } });
        await waitFor(() => expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual(['北川商事']));
    });

    it.each([false, true])('selects a highlighted result without submitting (chips: %s)', async (chips) => {
        const onSubmit = vi.fn();
        const onSelect = vi.fn();
        render(<Form chips={chips} onSubmit={onSubmit} onSelect={onSelect} />);
        const input = screen.getByRole('combobox');
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        fireEvent.change(input, { target: { value: 'Fuji' } });
        await waitFor(() => expect(input.getAttribute('aria-activedescendant')).toBeTruthy());
        expect(fireEvent.keyDown(input, { key: 'Enter' })).toBe(false);
        expect(onSelect).toHaveBeenCalledWith('Fuji Logistics', expect.objectContaining({ reason: 'item-press' }));
        expect(onSubmit).not.toHaveBeenCalled();
    });

    it('blocks Enter for an unmatched query and leaves it available for correction', async () => {
        render(<Form />);
        const input = screen.getByRole('combobox');
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        fireEvent.change(input, { target: { value: 'No match' } });
        await waitFor(() => expect(screen.queryAllByRole('option')).toHaveLength(0));
        expect(fireEvent.keyDown(input, { key: 'Enter' })).toBe(false);
        expect(input.getAttribute('aria-expanded')).toBe('true');
        expect(input).toHaveProperty('value', 'No match');
    });

    it('keeps the other choices visible for a selection and restores collapsed Enter on cancellation', async () => {
        render(<Picker initialSelection="Fuji Logistics" />);
        const input = screen.getByRole('combobox');
        act(() => input.focus());
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(100));
        fireEvent.change(input, { target: { value: 'Another company' } });
        fireEvent.keyDown(input, { key: 'Escape' });
        await waitFor(() => expect(input).toHaveProperty('value', 'Fuji Logistics'));
        expect(input.getAttribute('aria-expanded')).toBe('false');
        expect(fireEvent.keyDown(input, { key: 'Enter' })).toBe(true);
    });

    it('normalizes half-width kana before Base UI matching', async () => {
        render(<Form />);
        const input = screen.getByRole('combobox');
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        fireEvent.change(input, { target: { value: 'ｶﾞｰﾃﾞﾝ' } });
        await waitFor(() => expect(screen.getAllByRole('option').map((node) => node.textContent)).toEqual(['ガーデン商事']));
    });

    it('preserves externally filtered server results', async () => {
        render(<Form server />);
        fireEvent.keyDown(screen.getByRole('combobox'), { key: 'ArrowDown' });
        fireEvent.change(screen.getByRole('combobox'), { target: { value: 'Search handled by server' } });
        await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(100));
    });

    it.each([false, true])('waits for IME commit and ignores composition Enter (chips: %s)', async (chips) => {
        const onSelect = vi.fn();
        render(<Form chips={chips} onSelect={onSelect} />);
        const input = screen.getByRole('combobox');
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        await screen.findAllByRole('option');
        fireEvent.compositionStart(input);
        fireEvent.change(input, { target: { value: '北川' } });
        expect(screen.getAllByRole('option')).toHaveLength(100);
        expect(fireEvent.keyDown(input, { key: 'Enter', isComposing: true })).toBe(false);
        expect(onSelect).not.toHaveBeenCalled();
        fireEvent.compositionEnd(input, { data: '北川' });
        await waitFor(() => expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual(['北川商事']));
        expect(fireEvent.keyDown(input, { key: 'Enter', isComposing: true })).toBe(false);
        expect(onSelect).not.toHaveBeenCalled();
        expect(fireEvent.keyDown(input, { key: 'Enter', keyCode: 229 })).toBe(false);
        expect(onSelect).not.toHaveBeenCalled();
        fireEvent.keyDown(input, { key: 'Enter' });
        expect(onSelect).toHaveBeenCalledWith('北川商事', expect.objectContaining({ reason: 'item-press' }));
    });
});

describe('nested dismissal and safe focus', () => {
    it.each([false, true])('one Escape closes only the picker; Keep editing restores the draft field (drawer: %s)', async (drawer) => {
        render(<LayeredForm drawer={drawer} />);
        const draft = screen.getByLabelText('Draft');
        fireEvent.change(draft, { target: { value: 'Unsaved' } });
        const input = screen.getByRole('combobox');
        act(() => input.focus());
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        await screen.findByRole('listbox');
        fireEvent.keyDown(input, { key: 'Escape' });
        await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull());
        expect(screen.queryByText(messages.ConfirmDiscard.title)).toBeNull();
        act(() => draft.focus());
        fireEvent.keyDown(draft, { key: 'Escape' });
        const keep = await screen.findByRole('button', { name: messages.ConfirmDiscard.keepEditing });
        await waitFor(() => expect(document.activeElement).toBe(keep));
        fireEvent.click(keep);
        await waitFor(() => expect(document.activeElement).toBe(draft));
        expect(draft).toHaveProperty('value', 'Unsaved');
        const guards = document.querySelectorAll(drawer ? '[data-base-ui-focus-guard]' : '[data-radix-focus-guard]');
        const afterGuard = guards.item(guards.length - 1);
        if (!(afterGuard instanceof HTMLElement)) throw new Error('Missing modal focus guard');
        act(() => afterGuard.focus());
        await waitFor(() => expect(document.querySelector(drawer ? '[data-slot=drawer-content]' : '[data-slot=dialog-content]')
            ?.contains(document.activeElement)).toBe(true));
        act(() => draft.focus());
        fireEvent.keyDown(draft, { key: 'Escape' });
        const reopenedKeep = await screen.findByRole('button', { name: messages.ConfirmDiscard.keepEditing });
        const parentEscape = vi.fn();
        document.addEventListener('keydown', parentEscape, true);
        try {
            fireEvent.keyDown(reopenedKeep, { key: 'Escape' });
            expect(parentEscape).not.toHaveBeenCalled();
        } finally {
            document.removeEventListener('keydown', parentEscape, true);
        }
        await waitFor(() => expect(screen.queryByText(messages.ConfirmDiscard.title)).toBeNull());
        await waitFor(() => expect(document.activeElement).toBe(draft));
    });

    it('does not dismiss a dialog on an IME Escape', () => {
        render(<LayeredForm />);
        const draft = screen.getByLabelText('Draft');
        fireEvent.change(draft, { target: { value: '編集中' } });
        fireEvent.compositionStart(draft);
        fireEvent.keyDown(draft, { key: 'Escape' });
        expect(screen.queryByText(messages.ConfirmDiscard.title)).toBeNull();
        fireEvent.compositionEnd(draft);
        fireEvent.keyDown(draft, { key: 'Escape', isComposing: true });
        expect(screen.queryByText(messages.ConfirmDiscard.title)).toBeNull();
        expect(screen.getByRole('dialog')).toBeTruthy();
    });

    it.each([false, true])('localizes the shared close label (drawer: %s)', (drawer) => {
        render(<NextIntlClientProvider locale="ja" messages={jaMessages}>
            {drawer ? <Drawer open><DrawerContent><DrawerTitle>編集</DrawerTitle><DrawerDescription>連絡先を編集</DrawerDescription></DrawerContent></Drawer>
                : <Dialog open><DialogContent><DialogTitle>編集</DialogTitle><DialogDescription>連絡先を編集</DialogDescription></DialogContent></Dialog>}
        </NextIntlClientProvider>);
        expect(screen.getByRole('button', { name: '閉じる' })).toBeTruthy();
    });
});
