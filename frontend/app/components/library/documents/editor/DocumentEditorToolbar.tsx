'use client';

import { useEffect, useState } from 'react';
import type { Editor } from '@tiptap/core';
import { useTranslations } from 'next-intl';
import {
    AlignCenter,
    AlignJustify,
    AlignLeft,
    AlignRight,
    Bold,
    Code,
    Heading1,
    Heading2,
    Heading3,
    Italic,
    List,
    ListOrdered,
    Minus,
    Strikethrough,
    TableIcon,
    VariableIcon,
} from 'lucide-react';

import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { MERGE_FIELDS } from './documentFields';

type Props = { editor: Editor | null };

/**
 * Formatting, alignment, and insertion toolbar for the document block builder. Alignment covers the
 * document's left/center/right/justify layout needs; the field menu inserts an inline merge-token
 * chip, and the line-items button drops the pricing-table placeholder.
 */
export function DocumentEditorToolbar({ editor }: Props) {
    const t = useTranslations('DocumentTemplateBuilder');
    const [, setTick] = useState(0);

    useEffect(() => {
        if (!editor) return;
        const bump = () => setTick((tick) => tick + 1);
        editor.on('selectionUpdate', bump);
        editor.on('transaction', bump);
        return () => {
            editor.off('selectionUpdate', bump);
            editor.off('transaction', bump);
        };
    }, [editor]);

    if (!editor) return null;

    const button = (key: string, label: string, active: boolean, run: () => void, Icon: typeof Bold) => (
        <button
            key={key}
            type="button"
            aria-label={label}
            aria-pressed={active}
            title={label}
            onMouseDown={(event) => event.preventDefault()}
            onClick={run}
            className={`flex h-8 w-8 items-center justify-center rounded-md transition-colors ${
                active ? 'bg-accent text-foreground' : 'text-muted-foreground hover:bg-accent/60 hover:text-foreground'
            }`}
        >
            <Icon className="h-4 w-4" />
        </button>
    );

    const divider = (key: string) => <span key={key} className="mx-1 h-5 w-px bg-border" aria-hidden="true" />;

    const insertField = (token: string) =>
        editor
            .chain()
            .focus()
            .insertContent([{ type: 'mergeToken', attrs: { token } }, { type: 'text', text: ' ' }])
            .run();

    return (
        <div className="sticky top-0 z-10 mb-3 flex flex-wrap items-center gap-0.5 rounded-xl border border-border bg-card/85 p-1 backdrop-blur">
            {button('h1', t('formatH1'), editor.isActive('heading', { level: 1 }), () => editor.chain().focus().toggleHeading({ level: 1 }).run(), Heading1)}
            {button('h2', t('formatH2'), editor.isActive('heading', { level: 2 }), () => editor.chain().focus().toggleHeading({ level: 2 }).run(), Heading2)}
            {button('h3', t('formatH3'), editor.isActive('heading', { level: 3 }), () => editor.chain().focus().toggleHeading({ level: 3 }).run(), Heading3)}
            {divider('d1')}
            {button('bold', t('formatBold'), editor.isActive('bold'), () => editor.chain().focus().toggleBold().run(), Bold)}
            {button('italic', t('formatItalic'), editor.isActive('italic'), () => editor.chain().focus().toggleItalic().run(), Italic)}
            {button('strike', t('formatStrike'), editor.isActive('strike'), () => editor.chain().focus().toggleStrike().run(), Strikethrough)}
            {button('code', t('formatCode'), editor.isActive('code'), () => editor.chain().focus().toggleCode().run(), Code)}
            {divider('d2')}
            {button('alignLeft', t('alignLeft'), editor.isActive({ textAlign: 'left' }), () => editor.chain().focus().setTextAlign('left').run(), AlignLeft)}
            {button('alignCenter', t('alignCenter'), editor.isActive({ textAlign: 'center' }), () => editor.chain().focus().setTextAlign('center').run(), AlignCenter)}
            {button('alignRight', t('alignRight'), editor.isActive({ textAlign: 'right' }), () => editor.chain().focus().setTextAlign('right').run(), AlignRight)}
            {button('alignJustify', t('alignJustify'), editor.isActive({ textAlign: 'justify' }), () => editor.chain().focus().setTextAlign('justify').run(), AlignJustify)}
            {divider('d3')}
            {button('bullet', t('formatBullet'), editor.isActive('bulletList'), () => editor.chain().focus().toggleBulletList().run(), List)}
            {button('ordered', t('formatOrdered'), editor.isActive('orderedList'), () => editor.chain().focus().toggleOrderedList().run(), ListOrdered)}
            {button('divider', t('formatDivider'), false, () => editor.chain().focus().setHorizontalRule().run(), Minus)}
            {divider('d4')}
            {button('lineItems', t('insertLineItems'), false, () => editor.chain().focus().insertContent([{ type: 'lineItems' }, { type: 'paragraph' }]).run(), TableIcon)}
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <button
                        type="button"
                        title={t('insertField')}
                        onMouseDown={(event) => event.preventDefault()}
                        className="flex h-8 items-center gap-1.5 rounded-md px-2 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent/60 hover:text-foreground"
                    >
                        <VariableIcon className="size-4" />
                        {t('insertField')}
                    </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                    {MERGE_FIELDS.map((field) => (
                        <DropdownMenuItem key={field.token} onSelect={() => insertField(field.token)}>
                            {t(field.labelKey)}
                        </DropdownMenuItem>
                    ))}
                </DropdownMenuContent>
            </DropdownMenu>
        </div>
    );
}
