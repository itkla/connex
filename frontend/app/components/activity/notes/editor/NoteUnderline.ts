import { Mark, mergeAttributes } from '@tiptap/core';

type MarkdownToken = {
    type?: string;
    tag?: string;
    nesting?: number;
    markup?: string;
};

type MarkdownInlineState = {
    src: string;
    pos: number;
    posMax: number;
    md: MarkdownIt;
    env: unknown;
    tokens: MarkdownToken[];
    push: (type: string, tag: string, nesting: number) => MarkdownToken;
};

type MarkdownIt = {
    inline: {
        parse: (source: string, markdownIt: MarkdownIt, env: unknown, tokens: MarkdownToken[]) => void;
        ruler: {
            before: (
                beforeName: string,
                ruleName: string,
                rule: (state: MarkdownInlineState, silent: boolean) => boolean,
            ) => void;
        };
    };
};

const configuredMarkdownInstances = new WeakSet<object>();

function isEscaped(source: string, index: number): boolean {
    let backslashes = 0;
    for (let cursor = index - 1; cursor >= 0 && source[cursor] === '\\'; cursor -= 1) {
        backslashes += 1;
    }
    return backslashes % 2 === 1;
}

function findUnderlineEnd(source: string, from: number, to: number): number {
    for (let cursor = from; cursor < to - 1; cursor += 1) {
        if (source[cursor] !== '+' || source[cursor + 1] !== '+') continue;
        if (isEscaped(source, cursor) || /\s/.test(source[cursor - 1] ?? '')) continue;
        return cursor;
    }
    return -1;
}

/** Parses the safe `++text++` Markdown extension used for persisted underline marks. */
export function parseNoteUnderline(state: MarkdownInlineState, silent: boolean): boolean {
    const start = state.pos;
    if (state.src.slice(start, start + 2) !== '++' || isEscaped(state.src, start)) return false;

    const contentStart = start + 2;
    if (contentStart >= state.posMax || /\s/.test(state.src[contentStart] ?? '')) return false;

    const end = findUnderlineEnd(state.src, contentStart, state.posMax);
    if (end < 0) return false;

    if (!silent) {
        const open = state.push('note_underline_open', 'u', 1);
        open.markup = '++';
        state.md.inline.parse(state.src.slice(contentStart, end), state.md, state.env, state.tokens);
        const close = state.push('note_underline_close', 'u', -1);
        close.markup = '++';
    }
    state.pos = end + 2;
    return true;
}

/** Underline mark with an HTML-free Markdown round trip. */
export const NoteUnderline = Mark.create({
    name: 'noteUnderline',

    parseHTML() {
        return [
            { tag: 'u' },
            {
                style: 'text-decoration',
                consuming: false,
                getAttrs: (value) => typeof value === 'string' && value.includes('underline') ? {} : false,
            },
        ];
    },

    renderHTML({ HTMLAttributes }) {
        return ['u', mergeAttributes(HTMLAttributes), 0];
    },

    addKeyboardShortcuts() {
        return {
            'Mod-u': () => this.editor.commands.toggleMark(this.name),
        };
    },

    addStorage() {
        return {
            markdown: {
                serialize: {
                    open: '++',
                    close: '++',
                    mixable: true,
                    expelEnclosingWhitespace: true,
                },
                parse: {
                    setup(markdownIt: MarkdownIt) {
                        if (configuredMarkdownInstances.has(markdownIt)) return;
                        configuredMarkdownInstances.add(markdownIt);
                        markdownIt.inline.ruler.before('emphasis', 'note-underline', parseNoteUnderline);
                    },
                },
            },
        };
    },
});
