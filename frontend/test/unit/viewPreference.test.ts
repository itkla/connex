import { describe, expect, it } from 'vitest';

import {
    effectiveListView,
    resolveViewPreference,
    viewPreferenceStorageKey,
} from '@/app/hooks/viewPreference';
import { isSelectableDisplayMode } from '@/app/components/records/types';

describe('viewPreferenceStorageKey', () => {
    it('isolates preferences by user, workspace, and browser surface', () => {
        expect(viewPreferenceStorageKey('contacts:view', 7, 11))
            .toBe('connex:view:7:11:contacts:view');
        expect(viewPreferenceStorageKey('contacts:view', 8, 11))
            .not.toBe(viewPreferenceStorageKey('contacts:view', 7, 11));
        expect(viewPreferenceStorageKey('contacts:view', 7, 12))
            .not.toBe(viewPreferenceStorageKey('contacts:view', 7, 11));
        expect(viewPreferenceStorageKey('deals:view', 7, 11))
            .not.toBe(viewPreferenceStorageKey('contacts:view', 7, 11));
    });

    it('uses stable anonymous placeholders while context is loading', () => {
        expect(viewPreferenceStorageKey('tasks:view', null, null))
            .toBe('connex:view:anon:none:tasks:view');
    });

    it.each(['tasks:queue', 'activities:filter'])(
        'scopes the %s list preference so it cannot leak across users or workspaces',
        (storageKey) => {
            expect(viewPreferenceStorageKey(storageKey, 7, 11))
                .toBe(`connex:view:7:11:${storageKey}`);
            expect(viewPreferenceStorageKey(storageKey, 7, 11))
                .not.toBe(viewPreferenceStorageKey(storageKey, 8, 11));
            expect(viewPreferenceStorageKey(storageKey, 7, 11))
                .not.toBe(viewPreferenceStorageKey(storageKey, 7, 12));
            expect(viewPreferenceStorageKey(storageKey, 7, 11)).not.toBe(storageKey);
        },
    );
});

describe('resolveViewPreference', () => {
    it('prefers a shareable URL view over scoped storage', () => {
        expect(resolveViewPreference('grid', 'table', 'kanban', isSelectableDisplayMode)).toBe('grid');
    });

    it('uses scoped storage when the URL has no selectable view', () => {
        expect(resolveViewPreference(null, 'kanban', 'table', isSelectableDisplayMode)).toBe('kanban');
    });

    it('rejects the viewport-only list mode from URL and storage', () => {
        expect(resolveViewPreference('list', 'list', 'table', isSelectableDisplayMode)).toBe('table');
    });
});

describe('effectiveListView', () => {
    it('forces the list below the mobile breakpoint without changing the desktop value', () => {
        const desktopPreference = 'kanban';

        expect(effectiveListView(desktopPreference, true)).toBe('list');
        expect(effectiveListView(desktopPreference, false)).toBe(desktopPreference);
    });
});
