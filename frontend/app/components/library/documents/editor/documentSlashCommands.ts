import {
    Heading1,
    Heading2,
    Heading3,
    List,
    ListOrdered,
    Minus,
    ScrollText,
    TableIcon,
    TextQuote,
    Type,
} from 'lucide-react';

import type { SlashCommandItem } from '@/app/components/activity/notes/editor/slashCommands';

type Translate = (key: string) => string;

/**
 * Slash-command registry for the document block builder. Reuses the shared {@link SlashCommandItem}
 * contract so the notes {@code SlashCommand} extension and menu render it unchanged. Line items insert
 * the placeholder node; terms and footer insert aligned scaffolds the author fills in.
 */
export function buildDocumentSlashCommands(t: Translate): SlashCommandItem[] {
    return [
        {
            id: 'text',
            title: t('slashText'),
            subtitle: t('slashTextHint'),
            keywords: ['text', 'paragraph', 'plain', 'body'],
            icon: Type,
            run: (editor, range) => editor.chain().focus().deleteRange(range).setParagraph().run(),
        },
        {
            id: 'heading1',
            title: t('slashH1'),
            subtitle: t('slashH1Hint'),
            keywords: ['h1', 'heading', 'title', 'large'],
            icon: Heading1,
            run: (editor, range) => editor.chain().focus().deleteRange(range).toggleHeading({ level: 1 }).run(),
        },
        {
            id: 'heading2',
            title: t('slashH2'),
            subtitle: t('slashH2Hint'),
            keywords: ['h2', 'heading', 'subtitle'],
            icon: Heading2,
            run: (editor, range) => editor.chain().focus().deleteRange(range).toggleHeading({ level: 2 }).run(),
        },
        {
            id: 'heading3',
            title: t('slashH3'),
            subtitle: t('slashH3Hint'),
            keywords: ['h3', 'heading', 'small'],
            icon: Heading3,
            run: (editor, range) => editor.chain().focus().deleteRange(range).toggleHeading({ level: 3 }).run(),
        },
        {
            id: 'bulletList',
            title: t('slashBullet'),
            subtitle: t('slashBulletHint'),
            keywords: ['bullet', 'list', 'unordered', 'ul'],
            icon: List,
            run: (editor, range) => editor.chain().focus().deleteRange(range).toggleBulletList().run(),
        },
        {
            id: 'orderedList',
            title: t('slashOrdered'),
            subtitle: t('slashOrderedHint'),
            keywords: ['ordered', 'numbered', 'list', 'ol'],
            icon: ListOrdered,
            run: (editor, range) => editor.chain().focus().deleteRange(range).toggleOrderedList().run(),
        },
        {
            id: 'blockquote',
            title: t('slashQuote'),
            subtitle: t('slashQuoteHint'),
            keywords: ['quote', 'blockquote', 'citation'],
            icon: TextQuote,
            run: (editor, range) => editor.chain().focus().deleteRange(range).toggleBlockquote().run(),
        },
        {
            id: 'divider',
            title: t('slashDivider'),
            subtitle: t('slashDividerHint'),
            keywords: ['divider', 'hr', 'rule', 'separator', 'line'],
            icon: Minus,
            run: (editor, range) => editor.chain().focus().deleteRange(range).setHorizontalRule().run(),
        },
        {
            id: 'lineItems',
            title: t('slashLineItems'),
            subtitle: t('slashLineItemsHint'),
            keywords: ['line', 'items', 'table', 'products', 'pricing', 'totals'],
            icon: TableIcon,
            run: (editor, range) =>
                editor
                    .chain()
                    .focus()
                    .deleteRange(range)
                    .insertContent([{ type: 'lineItems' }, { type: 'paragraph' }])
                    .run(),
        },
        {
            id: 'terms',
            title: t('slashTerms'),
            subtitle: t('slashTermsHint'),
            keywords: ['terms', 'conditions', 'legal', 'clause'],
            icon: ScrollText,
            run: (editor, range) =>
                editor
                    .chain()
                    .focus()
                    .deleteRange(range)
                    .insertContent([
                        { type: 'heading', attrs: { level: 3 }, content: [{ type: 'text', text: t('slashTermsHeading') }] },
                        { type: 'paragraph' },
                    ])
                    .run(),
        },
        {
            id: 'footer',
            title: t('slashFooter'),
            subtitle: t('slashFooterHint'),
            keywords: ['footer', 'bottom', 'signature', 'closing'],
            icon: Minus,
            run: (editor, range) =>
                editor
                    .chain()
                    .focus()
                    .deleteRange(range)
                    .insertContent([
                        { type: 'horizontalRule' },
                        { type: 'paragraph', attrs: { textAlign: 'center' } },
                    ])
                    .run(),
        },
    ];
}
