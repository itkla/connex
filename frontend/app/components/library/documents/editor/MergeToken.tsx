import { Node, mergeAttributes } from '@tiptap/core';
import { NodeViewWrapper, ReactNodeViewRenderer, type NodeViewProps } from '@tiptap/react';
import { useTranslations } from 'next-intl';

import { mergeFieldLabelKey } from './documentFields';

/**
 * Inline, atomic merge-field chip. Persisted as a {@code mergeToken} node carrying the raw
 * {@code token} (e.g. {@code company.name}); the server flattens it to properly-escaped text when a
 * document is generated, so a chip never reaches the reader — only its resolved value does.
 */
export const MergeToken = Node.create({
    name: 'mergeToken',
    group: 'inline',
    inline: true,
    atom: true,
    selectable: true,
    draggable: false,

    addAttributes() {
        return {
            token: {
                default: null,
                parseHTML: (element) => element.getAttribute('data-token'),
                renderHTML: (attributes) =>
                    attributes.token ? { 'data-token': attributes.token as string } : {},
            },
        };
    },

    parseHTML() {
        return [{ tag: 'span[data-token][data-merge-token]' }];
    },

    renderHTML({ node, HTMLAttributes }) {
        return [
            'span',
            mergeAttributes(HTMLAttributes, { 'data-merge-token': '' }),
            `{{${node.attrs.token as string}}}`,
        ];
    },

    addNodeView() {
        return ReactNodeViewRenderer(MergeTokenView);
    },
});

function MergeTokenView({ node }: NodeViewProps) {
    const t = useTranslations('DocumentTemplateBuilder');
    const token = (node.attrs.token as string) ?? '';
    const labelKey = mergeFieldLabelKey(token);
    const label = labelKey ? t(labelKey) : token;
    return (
        <NodeViewWrapper as="span" className="inline">
            <span
                contentEditable={false}
                data-merge-token=""
                data-token={token}
                className="mx-px inline-flex items-center rounded-md bg-brand-light/60 px-1.5 py-0.5 align-baseline text-[0.85em] font-medium text-brand-dark ring-1 ring-brand/20"
            >
                {label}
            </span>
        </NodeViewWrapper>
    );
}
