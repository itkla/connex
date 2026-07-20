'use client';

import { useEffect, useRef } from 'react';
import { EditorContent, useEditor } from '@tiptap/react';
import type { Editor } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import { Placeholder } from '@tiptap/extension-placeholder';
import TextAlign from '@tiptap/extension-text-align';
import { useTranslations } from 'next-intl';

import { SlashCommand } from '@/app/components/activity/notes/editor/SlashCommand';
import { DocumentEditorToolbar } from './DocumentEditorToolbar';
import { buildDocumentSlashCommands } from './documentSlashCommands';
import { MergeToken } from './MergeToken';
import { LineItemsBlock } from './LineItemsBlock';

function readJson(editor: Editor): string {
    return JSON.stringify(editor.getJSON());
}

type Props = {
    value: string | null;
    onChange: (json: string) => void;
    editable?: boolean;
};

/**
 * WYSIWYG block editor for a document template body. Persists ProseMirror/Tiptap JSON (not Markdown,
 * unlike the notes editor) so block structure, per-block alignment, inline merge-token chips, and the
 * line-items placeholder survive losslessly for server-side resolution at generation.
 */
export default function DocumentBodyEditor({ value, onChange, editable = true }: Props) {
    const t = useTranslations('DocumentTemplateBuilder');
    const onChangeRef = useRef(onChange);
    const loadingRef = useRef(false);

    useEffect(() => {
        onChangeRef.current = onChange;
    }, [onChange]);

    const editor = useEditor({
        editable,
        immediatelyRender: false,
        extensions: [
            StarterKit.configure({ heading: { levels: [1, 2, 3] } }),
            Placeholder.configure({ placeholder: t('bodyPlaceholder') }),
            TextAlign.configure({ types: ['heading', 'paragraph'] }),
            MergeToken,
            LineItemsBlock,
            SlashCommand.configure({ commands: buildDocumentSlashCommands(t) }),
        ],
        editorProps: {
            attributes: {
                class: 'note-prose document-body min-h-[24rem] max-w-none focus:outline-none',
            },
        },
        onUpdate: ({ editor: instance }) => {
            if (loadingRef.current) return;
            onChangeRef.current(readJson(instance));
        },
    });

    useEffect(() => {
        if (!editor) return;
        const incoming = value && value.trim() ? value : null;
        if (incoming === null) {
            if (!editor.isEmpty) {
                loadingRef.current = true;
                editor.commands.clearContent();
                loadingRef.current = false;
            }
            return;
        }
        if (readJson(editor) === incoming) return;
        try {
            const parsed: unknown = JSON.parse(incoming);
            loadingRef.current = true;
            editor.commands.setContent(parsed as Parameters<typeof editor.commands.setContent>[0]);
            loadingRef.current = false;
        } catch {
            loadingRef.current = false;
        }
    }, [editor, value]);

    useEffect(() => {
        editor?.setEditable(editable);
    }, [editor, editable]);

    return (
        <div>
            {editable ? <DocumentEditorToolbar editor={editor} /> : null}
            <EditorContent editor={editor} />
        </div>
    );
}
