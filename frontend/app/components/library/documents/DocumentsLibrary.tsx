'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { PlusIcon, ShieldCheckIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import { SegmentedControl } from '@/components/ui/segmented-control';
import Rise from '@/app/components/motion/Rise';
import { PageHeader } from '@/app/components/PageHeader';
import { PageShell } from '@/app/components/PageShell';
import DocumentTemplatesBrowser from '@/app/components/library/documents/DocumentTemplatesBrowser';
import GeneratedDocumentsBrowser from '@/app/components/library/documents/GeneratedDocumentsBrowser';
import type { DocumentTemplate, User } from '@/app/lib/types';

/** Which of the library's two document surfaces is showing. */
type DocumentsView = 'documents' | 'templates';

/**
 * The library's documents surface. It holds two related but distinct things, so it names them
 * apart rather than letting one stand for both: the documents a workspace has generated, and the
 * document templates that produce them. Templates were the only thing here before, which is why a
 * finished quote could not be found without its deal.
 *
 * @param templates - the workspace's document templates
 * @param owners - workspace members, used to name the deal owner behind a generated document
 */
export default function DocumentsLibrary({
    templates,
    owners,
}: {
    templates: DocumentTemplate[];
    owners: User[];
}) {
    const t = useTranslations('DocumentsLibrary');
    const router = useRouter();
    const [view, setView] = useState<DocumentsView>('documents');

    return (
        <PageShell>
            <Rise>
                <PageHeader
                    title={t('title')}
                    description={view === 'documents' ? t('documentsDescription') : t('templatesDescription')}
                    actions={
                        view === 'templates' ? (
                            <>
                                <Button variant="outline" onClick={() => router.push('/records/approval-policies')}>
                                    <ShieldCheckIcon className="size-4" />
                                    {t('approvalPoliciesLink')}
                                </Button>
                                <Button variant="brand" onClick={() => router.push('/library/documents/new')}>
                                    <PlusIcon className="size-4" />
                                    {t('newTemplate')}
                                </Button>
                            </>
                        ) : null
                    }
                />
            </Rise>

            <Rise delay={0.04}>
                <SegmentedControl<DocumentsView>
                    ariaLabel={t('viewSwitchAria')}
                    value={view}
                    onChange={setView}
                    options={[
                        { value: 'documents', label: t('viewDocuments') },
                        { value: 'templates', label: t('viewTemplates') },
                    ]}
                />
            </Rise>

            {view === 'documents' ? (
                <GeneratedDocumentsBrowser owners={owners} />
            ) : (
                <DocumentTemplatesBrowser templates={templates} />
            )}
        </PageShell>
    );
}
