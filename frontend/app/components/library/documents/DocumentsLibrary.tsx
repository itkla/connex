'use client';

import { useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { PlusIcon, ShieldCheckIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import { SegmentedControl } from '@/components/ui/segmented-control';
import Rise from '@/app/components/motion/Rise';
import { PageHeader } from '@/app/components/PageHeader';
import { PageShell } from '@/app/components/PageShell';
import { useOwnedUrlParams } from '@/app/hooks/useOwnedUrlParams';
import DocumentTemplatesBrowser from '@/app/components/library/documents/DocumentTemplatesBrowser';
import GeneratedDocumentsBrowser from '@/app/components/library/documents/GeneratedDocumentsBrowser';
import { settingsDestination } from '@/app/lib/settingsEntryPoints';
import type { DocumentTemplate, User } from '@/app/lib/types';

/** Which of the library's two document surfaces is showing. */
type DocumentsView = 'documents' | 'templates';

/**
 * URL key this page owns for its view. Deliberately not `view`, which the records browsers own for
 * their own display mode; a shared key would let two writers overwrite each other's state.
 */
const VIEW_URL_KEY = 'list';

function normalizeView(value: string | null): DocumentsView {
    return value === 'templates' ? 'templates' : 'documents';
}

/**
 * The library's documents surface. It holds two related but distinct things, so it names them
 * apart rather than letting one stand for both: the documents a workspace has generated, and the
 * document templates that produce them. Templates were the only thing here before, which is why a
 * finished quote could not be found without its deal.
 *
 * Which half is showing lives in the URL, so either can be linked, shared, and restored on a
 * refresh — the same contract the records lists follow.
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
    const approvalPolicies = settingsDestination('workspace.approval-policies');
    const router = useRouter();
    const searchParams = useSearchParams();
    const [view, setView] = useState<DocumentsView>(() => normalizeView(searchParams.get(VIEW_URL_KEY)));

    useOwnedUrlParams({ [VIEW_URL_KEY]: view === 'documents' ? undefined : view });

    return (
        <PageShell>
            <Rise>
                <PageHeader
                    title={t('title')}
                    description={view === 'documents' ? t('documentsDescription') : t('templatesDescription')}
                    actions={
                        view === 'templates' ? (
                            <>
                                <Button variant="outline" onClick={() => router.push(approvalPolicies.href)}>
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
