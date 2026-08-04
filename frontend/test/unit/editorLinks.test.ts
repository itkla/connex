import { describe, expect, it } from 'vitest';

import { normalizeEditorLinkHref } from '@/app/components/activity/notes/editor/editorLinks';

describe('normalizeEditorLinkHref', () => {
    it.each([
        ['example.com', 'https://example.com'],
        ['https://example.com/docs', 'https://example.com/docs'],
        ['mailto:hello@example.com', 'mailto:hello@example.com'],
        ['tel:+18085550123', 'tel:+18085550123'],
        ['/records/contacts/42', '/records/contacts/42'],
        ['#next-steps', '#next-steps'],
    ])('normalizes %s', (input, expected) => {
        expect(normalizeEditorLinkHref(input)).toBe(expected);
    });

    it.each([
        '',
        'javascript:alert(1)',
        'data:text/html,<script>alert(1)</script>',
        '//evil.example',
        'https://example.com/has space',
    ])('rejects unsafe or malformed value %s', (input) => {
        expect(normalizeEditorLinkHref(input)).toBeNull();
    });
});
