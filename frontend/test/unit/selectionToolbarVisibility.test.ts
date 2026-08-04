import { describe, expect, it } from 'vitest';

import { canShowSelectionToolbar } from '@/app/components/activity/notes/editor/selectionToolbarVisibility';

const visibleContext = {
    editable: true,
    textSelection: true,
    from: 4,
    to: 12,
    codeBlock: false,
};

describe('canShowSelectionToolbar', () => {
    it('shows for a non-empty editable text selection', () => {
        expect(canShowSelectionToolbar(visibleContext)).toBe(true);
    });

    it.each([
        { editable: false },
        { textSelection: false },
        { to: visibleContext.from },
        { codeBlock: true },
    ])('hides for unsupported context %o', (override) => {
        expect(canShowSelectionToolbar({ ...visibleContext, ...override })).toBe(false);
    });
});
