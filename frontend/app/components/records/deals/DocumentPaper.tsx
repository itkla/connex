'use client';

import { useEffect } from 'react';
import { useTranslations } from 'next-intl';

import { Button } from '@/components/ui/button';
import DocumentView from '@/app/components/records/documents/DocumentView';
import type { DealDocument } from '@/app/lib/types';

/**
 * Print-ready page for an immutable generated document. Opened in its own tab and printed via the
 * browser (print-to-PDF) — the same mechanism the reports feature uses, so CJK glyphs render through
 * the loaded web font and the output stays vector and selectable. The paper body is the shared
 * {@link DocumentView}, so print output matches the builder preview exactly.
 */
export default function DocumentPaper({ document: doc }: { document: DealDocument | null }) {
    const tp = useTranslations('DealsDocuments.print');

    useEffect(() => {
        if (!doc) return;
        let cancelled = false;
        const trigger = () => { if (!cancelled) window.print(); };
        const fonts = (window.document as Document & { fonts?: FontFaceSet }).fonts;
        const ready = fonts?.ready ?? Promise.resolve();
        const timer = window.setTimeout(() => ready.then(trigger).catch(trigger), 350);
        return () => { cancelled = true; window.clearTimeout(timer); };
    }, [doc]);

    if (!doc) {
        return (
            <div className="mx-auto max-w-2xl px-6 py-24 text-center text-sm text-muted-foreground">
                {tp('notFound')}
            </div>
        );
    }

    return (
        <div className="document-page min-h-full bg-muted/40 px-4 py-10 print:bg-white print:p-0">
            <div className="document-controls mx-auto mb-6 flex max-w-[52rem] items-center justify-end">
                <Button variant="brand" onClick={() => window.print()}>
                    {tp('printButton')}
                </Button>
            </div>

            <article className="document-paper mx-auto max-w-[52rem] rounded-2xl border border-border bg-card px-12 py-14 shadow-sm print:rounded-none print:border-0 print:px-0 print:py-0 print:shadow-none">
                <DocumentView
                    content={doc.content}
                    type={doc.type}
                    status={doc.status}
                    version={doc.version}
                    generatedAt={doc.generatedAt}
                />
            </article>
        </div>
    );
}
