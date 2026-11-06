/** @vitest-environment jsdom */
import { useState } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { MultiSelectFilter } from '@/app/components/filters/FilterPill';
import { createServerPickerSearch } from '@/lib/server-picker-search';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => setTimeout(() => callback(0), 0));
    vi.stubGlobal('cancelAnimationFrame', clearTimeout);
    HTMLElement.prototype.scrollIntoView = vi.fn();
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function SearchFilter() {
    const [query, setQuery] = useState('');
    const [selected, setSelected] = useState(new Set<string>());
    const item = query ? { id: '88', label: 'Outside the first fifty' } : { id: '1', label: 'First page' };
    return <><MultiSelectFilter label="Contacts" ariaLabel="Filter contacts" options={[{ value: item.id, label: item.label }]}
        selected={selected} onToggle={(value) => setSelected(new Set([...selected, value]))} onClear={() => setSelected(new Set())} clearLabel="Clear"
        search={{ value: query, onChange: setQuery, state: createServerPickerSearch(query, { query, items: [item] }) }} />
        <output aria-label="Selection">{[...selected].join(',')}</output></>;
}

it('keyboard opening focuses search and permits navigation to a result beyond the initial bounded set', async () => {
    render(<SearchFilter />);
    const trigger = screen.getByRole('button', { name: 'Filter contacts' });
    act(() => trigger.focus());
    fireEvent.keyDown(trigger, { key: 'Enter' });
    const input = await screen.findByRole('textbox', { name: 'Contacts' });
    await waitFor(() => expect(document.activeElement).toBe(input));
    fireEvent.keyDown(input, { key: 'Tab' });
    expect(document.activeElement).toBe(screen.getByRole('menuitemcheckbox', { name: 'First page' }));
    fireEvent.keyDown(document.activeElement ?? input, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(input);
    fireEvent.compositionStart(input);
    fireEvent.keyDown(input, { key: 'Escape', isComposing: true, keyCode: 229 });
    expect(screen.getByRole('menu')).toBeDefined();
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(input);
    fireEvent.keyDown(input, { key: 'ArrowDown', isComposing: true, keyCode: 229 });
    fireEvent.compositionEnd(input);
    expect(document.activeElement).toBe(input);
    fireEvent.change(input, { target: { value: 'server query' } });
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    const remote = screen.getByRole('menuitemcheckbox', { name: 'Outside the first fifty' });
    expect(document.activeElement).toBe(remote);
    fireEvent.keyDown(remote, { key: 'ArrowUp' });
    expect(document.activeElement).toBe(input);
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(remote, { key: ' ' });
    expect(screen.getByLabelText('Selection').textContent).toBe('88');
    fireEvent.keyDown(remote, { key: 'Tab' });
    expect(document.activeElement).toBe(input);
    fireEvent.keyDown(input, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(trigger));
});
