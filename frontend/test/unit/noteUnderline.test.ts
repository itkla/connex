import { Editor } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import { describe, expect, it } from 'vitest';
import { Markdown } from 'tiptap-markdown';

import {
    NoteUnderline,
    parseNoteUnderline,
} from '@/app/components/activity/notes/editor/NoteUnderline';

describe('NoteUnderline', () => {
    it('serializes underline without enabling raw HTML', () => {
        const editor = new Editor({
            extensions: [
                StarterKit.configure({ underline: false }),
                NoteUnderline,
                Markdown.configure({ html: false }),
            ],
            content: {
                type: 'doc',
                content: [{
                    type: 'paragraph',
                    content: [{
                        type: 'text',
                        marks: [{ type: 'noteUnderline' }],
                        text: 'important',
                    }],
                }],
            },
        });

        const storage = editor.storage as { markdown?: { getMarkdown?: () => string } };
        expect(storage.markdown?.getMarkdown?.()).toBe('++important++');
        editor.destroy();
    });

    it('parses bounded underline delimiters', () => {
        const pushed: Array<{ type: string; tag: string; nesting: number; markup?: string }> = [];
        const state = {
            src: '++important++',
            pos: 0,
            posMax: 13,
            env: {},
            tokens: pushed,
            md: {
                inline: {
                    parse: (source: string) => {
                        pushed.push({ type: 'text', tag: '', nesting: 0, markup: source });
                    },
                    ruler: { before: () => undefined },
                },
            },
            push: (type: string, tag: string, nesting: number) => {
                const token = { type, tag, nesting };
                pushed.push(token);
                return token;
            },
        };

        expect(parseNoteUnderline(state, false)).toBe(true);
        expect(state.pos).toBe(13);
        expect(pushed.map(({ type, tag, nesting }) => ({ type, tag, nesting }))).toEqual([
            { type: 'note_underline_open', tag: 'u', nesting: 1 },
            { type: 'text', tag: '', nesting: 0 },
            { type: 'note_underline_close', tag: 'u', nesting: -1 },
        ]);

    });

    it.each([
        ['C++ and D++', 1],
        ['++++', 0],
        ['C++++', 1],
        ['++literal++++', 0],
        ['\\++literal++', 1],
    ])('preserves literal plus run %s', (source, pos) => {
        const state = {
            src: source,
            pos,
            posMax: source.length,
            env: {},
            tokens: [],
            md: {
                inline: {
                    parse: () => undefined,
                    ruler: { before: () => undefined },
                },
            },
            push: () => ({}),
        };

        expect(parseNoteUnderline(state, true)).toBe(false);
        expect(state.pos).toBe(pos);
    });
});
