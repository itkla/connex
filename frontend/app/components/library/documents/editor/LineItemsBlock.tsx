import { Node, mergeAttributes } from '@tiptap/core';
import { NodeViewWrapper, ReactNodeViewRenderer, type NodeViewProps } from '@tiptap/react';
import { useTranslations } from 'next-intl';
import { TableIcon } from 'lucide-react';

/**
 * Block-level placeholder for the deal's line-items table and totals. Persisted as an empty
 * {@code lineItems} node; at generation the reader's document expands it into the frozen line items,
 * so the builder shows an illustrative preview here rather than real figures.
 */
export const LineItemsBlock = Node.create({
    name: 'lineItems',
    group: 'block',
    atom: true,
    selectable: true,
    draggable: true,

    parseHTML() {
        return [{ tag: 'div[data-line-items]' }];
    },

    renderHTML({ HTMLAttributes }) {
        return ['div', mergeAttributes(HTMLAttributes, { 'data-line-items': '' })];
    },

    addNodeView() {
        return ReactNodeViewRenderer(LineItemsView);
    },
});

function LineItemsView({ selected }: NodeViewProps) {
    const t = useTranslations('DocumentTemplateBuilder');
    return (
        <NodeViewWrapper
            data-line-items=""
            className={`my-3 rounded-xl border border-dashed bg-muted/30 p-4 transition-colors ${
                selected ? 'border-brand ring-2 ring-brand/30' : 'border-border'
            }`}
        >
            <div contentEditable={false} className="select-none">
                <div className="mb-3 flex items-center gap-2 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                    <TableIcon className="size-3.5" />
                    {t('lineItemsBlockLabel')}
                </div>
                <div className="overflow-hidden rounded-lg border border-border/70 bg-card/60">
                    <div className="grid grid-cols-[1fr_auto_auto] gap-x-6 border-b border-border/70 px-3 py-1.5 text-[0.7rem] uppercase tracking-[0.08em] text-muted-foreground">
                        <span>{t('lineItemsSampleItem')}</span>
                        <span className="text-right">{t('lineItemsSampleQty')}</span>
                        <span className="text-right">{t('lineItemsSampleAmount')}</span>
                    </div>
                    {[0, 1].map((row) => (
                        <div
                            key={row}
                            className="grid grid-cols-[1fr_auto_auto] gap-x-6 px-3 py-1.5 text-xs text-muted-foreground/80"
                        >
                            <span className="h-2 w-32 rounded bg-muted-foreground/20" />
                            <span className="justify-self-end h-2 w-6 rounded bg-muted-foreground/20" />
                            <span className="justify-self-end h-2 w-14 rounded bg-muted-foreground/20" />
                        </div>
                    ))}
                    <div className="grid grid-cols-[1fr_auto] gap-x-6 border-t border-border/70 px-3 py-1.5 text-xs">
                        <span className="justify-self-end text-muted-foreground">{t('lineItemsSampleTotal')}</span>
                        <span className="justify-self-end h-2 w-16 rounded bg-muted-foreground/30" />
                    </div>
                </div>
                <p className="mt-2 text-[0.7rem] text-muted-foreground">{t('lineItemsBlockHint')}</p>
            </div>
        </NodeViewWrapper>
    );
}
