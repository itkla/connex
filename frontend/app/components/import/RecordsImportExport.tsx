'use client';

import { useState } from 'react';
import { useTranslations } from 'next-intl';
import { ArrowDownTrayIcon, ArrowUpTrayIcon } from '@heroicons/react/24/outline';

import { Button } from '@/components/ui/button';
import { exportCompaniesCsv, exportContactsCsv, exportDealsCsv } from '@/app/lib/api';
import { toastError } from '@/app/lib/toast';
import type { ContactsPageParams, ImportEntity } from '@/app/lib/types';
import ImportDialog from './ImportDialog';

/**
 * Toolbar Export + Import controls for a records list. Export downloads the current view as CSV;
 * Import opens the {@link ImportDialog} wizard and calls {@code onImported} after a successful commit.
 */
export type RecordsImportExportProps = {
    entity: ImportEntity;
    onImported: () => void;
    contactsFilter?: ContactsPageParams;
};

export default function RecordsImportExport({ entity, onImported, contactsFilter }: RecordsImportExportProps) {
    const t = useTranslations('importExport');
    const [importOpen, setImportOpen] = useState(false);
    const [exporting, setExporting] = useState(false);

    async function handleExport() {
        setExporting(true);
        try {
            if (entity === 'persons') await exportContactsCsv(contactsFilter);
            else if (entity === 'companies') await exportCompaniesCsv();
            else await exportDealsCsv();
        } catch {
            toastError(t('errorExport'));
        } finally {
            setExporting(false);
        }
    }

    return (
        <>
            <Button variant="outline" onClick={handleExport} disabled={exporting}>
                <ArrowDownTrayIcon />
                {t('exportCsv')}
            </Button>
            <Button variant="outline" onClick={() => setImportOpen(true)}>
                <ArrowUpTrayIcon />
                {t('openImport')}
            </Button>
            <ImportDialog entity={entity} open={importOpen} onOpenChange={setImportOpen} onImported={onImported} />
        </>
    );
}
