'use client';

import { useState } from 'react';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';
import { ArrowDownTrayIcon, ArrowUpTrayIcon } from '@heroicons/react/24/outline';
import { ChevronDownIcon, PlusIcon } from '@heroicons/react/24/solid';

import { Button } from '@/components/ui/button';
import { ButtonGroup, ButtonGroupSeparator } from '@/components/ui/button-group';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { exportCompaniesCsv, exportContactsCsv, exportDealsCsv } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { ContactsPageParams, ImportEntity } from '@/app/lib/types';
import ImportDialog from './ImportDialog';

/**
 * Records list header actions rendered as a split button: the primary "New" action on the left, joined
 * to a dropdown holding Import and Export. Export downloads the current view as CSV; Import opens the
 * {@link ImportDialog} wizard and calls {@code onImported} after a successful commit.
 */
export type RecordsActionsProps = {
    entity: ImportEntity;
    onNew: () => void;
    newLabel: string;
    newAriaLabel: string;
    onImported: () => void;
    contactsFilter?: ContactsPageParams;
    exportIds?: number[];
};

export default function RecordsActions({
    entity,
    onNew,
    newLabel,
    newAriaLabel,
    onImported,
    contactsFilter,
    exportIds,
}: RecordsActionsProps) {
    const t = useTranslations('importExport');
    const [importOpen, setImportOpen] = useState(false);

    async function handleExport() {
        const toastId = toast.loading(t('exporting'));
        try {
            if (entity === 'persons') await exportContactsCsv(contactsFilter);
            else if (entity === 'companies') await exportCompaniesCsv(exportIds);
            else await exportDealsCsv(exportIds);
            toastSuccess(t('exported'), { id: toastId });
        } catch {
            toastError(t('errorExport'), { id: toastId });
        }
    }

    return (
        <>
            <ButtonGroup>
                <Button className="bg-brand text-white hover:bg-brand-hover" aria-label={newAriaLabel} onClick={onNew}>
                    <PlusIcon strokeWidth={2.5} />
                    {newLabel}
                </Button>
                <ButtonGroupSeparator className="bg-white/20" />
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <Button size="icon" className="bg-brand text-white hover:bg-brand-hover" aria-label={t('moreActions')}>
                            <ChevronDownIcon className="size-3.5 transition-transform duration-200 ease-out group-data-[state=open]/button:rotate-180 motion-reduce:transition-none" />
                        </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end" className="w-44">
                        <DropdownMenuItem onSelect={(e) => { e.preventDefault(); setImportOpen(true); }}>
                            <ArrowUpTrayIcon className="size-4" />
                            {t('openImport')}
                        </DropdownMenuItem>
                        <DropdownMenuItem onSelect={() => handleExport()}>
                            <ArrowDownTrayIcon className="size-4" />
                            {t('exportCsv')}
                        </DropdownMenuItem>
                    </DropdownMenuContent>
                </DropdownMenu>
            </ButtonGroup>
            <ImportDialog entity={entity} open={importOpen} onOpenChange={setImportOpen} onImported={onImported} />
        </>
    );
}
