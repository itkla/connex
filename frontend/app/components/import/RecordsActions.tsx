'use client';

import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { usePathname } from 'next/navigation';
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
import { useActions, useRegisterActions } from '@/app/hooks/useActions';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import type { ActionContext, AppAction } from '@/app/lib/actions/types';
import ImportDialog from './ImportDialog';

type CurrentViewExportActionConfig = {
    id: string;
    labelKey: string;
    descriptionKey: string;
    keywordsKey: string;
    order: number;
};

const CURRENT_VIEW_EXPORT_ACTIONS = {
    companies: {
        id: 'utility.export-current-companies',
        labelKey: 'utility.exportCurrentCompanies',
        descriptionKey: 'description.utility.exportCurrentCompanies',
        keywordsKey: 'keywords.utility.exportCurrentCompanies',
        order: 60,
    },
    persons: {
        id: 'utility.export-current-contacts',
        labelKey: 'utility.exportCurrentContacts',
        descriptionKey: 'description.utility.exportCurrentContacts',
        keywordsKey: 'keywords.utility.exportCurrentContacts',
        order: 70,
    },
    deals: {
        id: 'utility.export-current-deals',
        labelKey: 'utility.exportCurrentDeals',
        descriptionKey: 'description.utility.exportCurrentDeals',
        keywordsKey: 'keywords.utility.exportCurrentDeals',
        order: 80,
    },
} satisfies Record<ImportEntity, CurrentViewExportActionConfig>;

function isAbortError(error: unknown): boolean {
    return error instanceof Error && error.name === 'AbortError';
}

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
    onExport: (signal: AbortSignal, workspaceId: number) => Promise<void>;
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
    const pathname = usePathname() ?? '';
    const { activeWorkspaceId, switching } = useWorkspace();
    const { pendingIds, run } = useActions();
    const [importOpen, setImportOpen] = useState(false);
    const [originPathname] = useState(pathname);
    const [originWorkspaceId] = useState(activeWorkspaceId);
    const activeControllerRef = useRef<AbortController | null>(null);
    const liveExportRef = useRef(onExport);
    const liveScopeRef = useRef({ active: true, activeWorkspaceId, pathname, switching });
    const actionConfig = CURRENT_VIEW_EXPORT_ACTIONS[entity];

    useLayoutEffect(() => {
        liveExportRef.current = onExport;
    }, [onExport]);

    const handleExport = useCallback(async (context: ActionContext) => {
        const liveScope = liveScopeRef.current;
        if (
            !liveScope.active
            || liveScope.switching
            || originWorkspaceId === null
            || liveScope.activeWorkspaceId !== originWorkspaceId
            || liveScope.pathname !== originPathname
            || context.workspace?.id !== originWorkspaceId
            || context.route.pathname !== originPathname
        ) return;
        const controller = new AbortController();
        activeControllerRef.current = controller;
        const toastId = toast.loading(t('exporting'));
        try {
            await liveExportRef.current(controller.signal, originWorkspaceId);
            if (!controller.signal.aborted) toastSuccess(t('exported'), { id: toastId });
        } catch (error) {
            if (controller.signal.aborted || isAbortError(error)) {
                toast.dismiss(toastId);
                return;
            }
            toastError(t('errorExport'), { id: toastId });
        } finally {
            if (activeControllerRef.current === controller) activeControllerRef.current = null;
        }
    }, [originPathname, originWorkspaceId, t]);

    useLayoutEffect(() => {
        liveScopeRef.current = { active: true, activeWorkspaceId, pathname, switching };
        return () => {
            liveScopeRef.current = { active: false, activeWorkspaceId, pathname, switching };
            activeControllerRef.current?.abort();
            activeControllerRef.current = null;
        };
    }, [activeWorkspaceId, pathname, switching]);

    const exportActions = useMemo<readonly AppAction[]>(
        () => !switching && activeWorkspaceId === originWorkspaceId && pathname === originPathname
            ? [
                {
                    id: actionConfig.id,
                    group: 'utility',
                    labelKey: actionConfig.labelKey,
                    descriptionKey: actionConfig.descriptionKey,
                    keywordsKey: actionConfig.keywordsKey,
                    icon: ArrowDownTrayIcon,
                    order: actionConfig.order,
                    isAvailable: (context) =>
                        context.workspace?.id === originWorkspaceId && context.route.pathname === originPathname,
                    execute: handleExport,
                },
            ]
            : [],
        [activeWorkspaceId, actionConfig, handleExport, originPathname, originWorkspaceId, pathname, switching],
    );

    useRegisterActions(exportActions);

    const exportPending = pendingIds.has(actionConfig.id);
    const runExport = useCallback(() => {
        void run(actionConfig.id, { source: 'menu' });
    }, [actionConfig.id, run]);

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
                    <DropdownMenuContent align="end" className="w-52">
                        <DropdownMenuItem onSelect={(e) => { e.preventDefault(); setImportOpen(true); }}>
                            <ArrowUpTrayIcon className="size-4" />
                            {t('openImport')}
                        </DropdownMenuItem>
                        <DropdownMenuItem disabled={exportPending} aria-busy={exportPending} onSelect={runExport}>
                            <ArrowDownTrayIcon className="size-4" />
                            {t('exportCurrentView')}
                        </DropdownMenuItem>
                    </DropdownMenuContent>
                </DropdownMenu>
            </ButtonGroup>
            <ImportDialog entity={entity} open={importOpen} onOpenChange={setImportOpen} onImported={onImported} />
        </>
    );
}
