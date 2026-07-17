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
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { ImportEntity } from '@/app/lib/types';
import ImportDialog from './ImportDialog';

/**
 * Records list header actions rendered as a split button: the primary "New" action on the left, joined
 * to a dropdown holding Import and Export. Export delegates to {@code onExport}, which each browser
 * implements against its own live filter/scope/segment state so the exported set equals the visible
 * filtered+scoped list. Import opens the {@link ImportDialog} wizard and calls {@code onImported} after
 * a successful commit.
 */
export type RecordsActionsProps = {
    entity: ImportEntity;
    onNew: () => void;
    newLabel: string;
    newAriaLabel: string;
    onImported: () => void;
    onExport: () => Promise<void>;
};

export default function RecordsActions({
    entity,
    onNew,
    newLabel,
    newAriaLabel,
    onImported,
    onExport,
}: RecordsActionsProps) {
    const t = useTranslations('importExport');
    const [importOpen, setImportOpen] = useState(false);

    async function handleExport() {
        const toastId = toast.loading(t('exporting'));
        try {
            await onExport();
            toastSuccess(t('exported'), { id: toastId });
        } catch {
            toastError(t('errorExport'), { id: toastId });
        }
    }

    return (
        <>
            <ButtonGroup>
                <Button variant="brand" aria-label={newAriaLabel} onClick={onNew}>
                    <PlusIcon strokeWidth={2.5} />
                    {newLabel}
                </Button>
                <ButtonGroupSeparator className="bg-brand-foreground/20" />
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <Button variant="brand" size="icon" className="border-r-0 data-[state=open]:[&>svg]:rotate-180" aria-label={t('moreActions')}>
                            <ChevronDownIcon className="size-3.5 transition-transform duration-200 ease-out motion-reduce:transition-none" />
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
