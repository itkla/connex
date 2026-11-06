import { describe, expect, it } from 'vitest';

import { isSearchComposing } from '@/app/lib/search/composition';

describe('global search IME guard', () => {
    it('ignores Enter and Escape while the input composition lifecycle is active', () => {
        expect(isSearchComposing({ isComposing: false, keyCode: 13 }, true)).toBe(true);
        expect(isSearchComposing({ isComposing: false, keyCode: 27 }, true)).toBe(true);
    });

    it('recognizes browser composition state and Safari confirmation after compositionend', () => {
        expect(isSearchComposing({ isComposing: true, keyCode: 13 }, false)).toBe(true);
        expect(isSearchComposing({ isComposing: false, keyCode: 229 }, false)).toBe(true);
    });

    it('allows a deliberate Enter or Escape after conversion', () => {
        expect(isSearchComposing({ isComposing: false, keyCode: 13 }, false)).toBe(false);
        expect(isSearchComposing({ isComposing: false, keyCode: 27 }, false)).toBe(false);
    });
});
