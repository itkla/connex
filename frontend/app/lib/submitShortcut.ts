import type { KeyboardEvent } from 'react';

/**
 * Whether a keypress is the Cmd/Ctrl+Enter submit shortcut and is NOT part of an active IME composition
 * (so a Japanese candidate-confirming Enter never submits the form). Callers should still gate on the
 * form's own can-submit condition before submitting.
 */
export function isSubmitShortcut(event: KeyboardEvent): boolean {
    return (event.metaKey || event.ctrlKey) && event.key === 'Enter' && !event.nativeEvent.isComposing;
}
