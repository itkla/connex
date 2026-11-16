'use client';

import { useRef, useState } from 'react';
import type { ComboboxRootChangeEventDetails } from '@base-ui/react/combobox';

/** Preserves an unresolved search through blur; selecting, clearing or cancelling an existing selection resolves it. */
export function usePickerDraft(selectedLabel: string, onQueryChange: (query: string) => void) {
    const [draft, setDraft] = useState<string | null>(null);
    const draftRef = useRef<string | null>(null);
    const pending = draft !== null && draft.trim() !== '';
    const commit = () => {
        draftRef.current = null;
        setDraft(null);
        onQueryChange('');
    };
    return {
        pending,
        commit,
        inputValue: draft ?? selectedLabel,
        onInputValueChange: (value: string, details: ComboboxRootChangeEventDetails) => {
            if (details.reason === 'input-change' || details.reason === 'clear-press') {
                draftRef.current = value.trim() ? value : null;
                setDraft(draftRef.current);
                onQueryChange(value);
            } else if (draftRef.current?.trim() && details.reason !== 'item-press') {
                details.cancel();
            }
        },
        onOpenChange: (open: boolean, details: ComboboxRootChangeEventDetails) => {
            if (!open && details.reason === 'escape-key' && selectedLabel) commit();
        },
    };
}
