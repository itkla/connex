'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { Button } from '@/components/ui/button';
import { ButtonGroup } from '@/components/ui/button-group';
import { toast } from 'sonner';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem } from '@/components/ui/dropdown-menu';
import { PlusIcon, FunnelIcon, TrashIcon, PencilIcon, EllipsisVerticalIcon, EyeIcon } from '@heroicons/react/24/solid';
import {
    MagnifyingGlassIcon,
    Squares2X2Icon,
    TableCellsIcon,
    ChevronDownIcon,
} from '@heroicons/react/24/outline';

import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import DeleteRecordDialog from '@/app/components/records/DeleteRecordDialog';
import { useRecordsBrowser } from '@/app/hooks/useRecordsBrowser';
import { type ColumnDef } from '@/app/components/records/types';
import DealCard from '@/app/components/records/deals/DealCard';
import DealAvatar from '@/app/components/records/deals/DealAvatar';
import NewDealDialog from '@/app/components/records/deals/NewDealDialog';
import QuickEditDealSheet, { type DealDraft } from '@/app/components/records/deals/QuickEditDealSheet';
import {
    createDeal,
    deleteDeal,
    updateDeal,
    getCompanies,
    getPipelines,
    getStagesByPipelineId,
    getDealPeople,
} from '@/app/lib/api';
import { formatCompactCurrency, formatDateTime } from '@/app/lib/utils';
import {
    type Company,
    type CreateDealPayload,
    type Deal,
    type Pipeline,
    type Stage,
    type Contact,
    type UpdateDealPayload,
} from '@/app/lib/types';
import { toMysqlDateTime, parseMysqlDateTime } from '@/app/lib/utils';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import ContactAvatar from '../contacts/ContactAvatar';
import SummaryTile from '@/app/components/SummaryTile';
import DealsRevenueChart from '@/app/components/records/deals/DealsRevenueChart';
import StageRatio from '@/app/components/records/deals/StageRatio';
import DealsAging from '@/app/components/records/deals/DealsAging';
import TopDeals from '@/app/components/records/deals/TopDeals';

function toDraft(d: Deal): DealDraft {
    return {
        name: d.name ?? '',
        value: d.value ?? 0,
        actualValue: d.actualValue ?? 0,
        currency: d.currency ?? 'USD',
        pipeline: d.pipeline ?? 0,
        stage: d.stage ?? 0,
        company: d.company ?? null,
        expectedCloseDate: d.expectedCloseDate ?? '',
        closedAt: d.closedAt ?? null,
    };
}

function diffDraft(original: DealDraft, draft: DealDraft): boolean {
    return (
        original.name !== draft.name ||
        original.value !== draft.value ||
        original.actualValue !== draft.actualValue ||
        original.currency !== draft.currency ||
        original.pipeline !== draft.pipeline ||
        original.stage !== draft.stage ||
        original.company !== draft.company ||
        original.expectedCloseDate !== draft.expectedCloseDate ||
        original.closedAt !== draft.closedAt
    );
}

function isClosed(deal: Deal): boolean {
    const t = parseMysqlDateTime(deal.closedAt);
    return Number.isFinite(t) && t <= Date.now();
}

export default function DealsBrowser({ deals }: { deals: Deal[] }) {
    const router = useRouter();

    const [companies, setCompanies] = useState<Company[]>([]);
    const [pipelines, setPipelines] = useState<Pipeline[]>([]);
    const [contactByDealId, setContactByDealId] = useState<Map<number, Contact>>(new Map());
    const [stagesByPipeline, setStagesByPipeline] = useState<Record<number, Stage[]>>({});

    useEffect(() => {
        getCompanies({}).then(setCompanies).catch(() => setCompanies([]));
        getPipelines().then(async (ps) => {
            setPipelines(ps);
            const entries = await Promise.all(
                ps.map(async (p) => [p.id, await getStagesByPipelineId(p.id).catch(() => [] as Stage[])] as const),
            );
            setStagesByPipeline(Object.fromEntries(entries));
        }).catch(() => setPipelines([]));
    }, []);

    useEffect(() => {
        const freelancerDeals = deals.filter((d) => d.company == null);
        if (freelancerDeals.length === 0) {
            setContactByDealId(new Map());
            return;
        }
        Promise.all(
            freelancerDeals.map(async (d) => {
                const people = await getDealPeople(d.id).catch(() => [] as Contact[]);
                return [d.id, people[0]] as const;
            }),
        ).then((entries) => {
            const m = new Map<number, Contact>();
            for (const [id, contact] of entries) {
                if (contact) m.set(id, contact);
            }
            setContactByDealId(m);
        });
    }, [deals]);

    const companyById = useMemo(() => new Map(companies.map((c) => [c.id, c])), [companies]);
    const pipelineById = useMemo(() => new Map(pipelines.map((p) => [p.id, p])), [pipelines]);
    const stageById = useMemo(() => {
        const m = new Map<number, Stage>();
        for (const stages of Object.values(stagesByPipeline)) {
            for (const s of stages) m.set(s.id, s);
        }
        return m;
    }, [stagesByPipeline]);
    
    const searchFields = useCallback((d: Deal) => [
        d.name,
        d.currency,
        d.company != null ? companyById.get(d.company)?.name : undefined,
        d.pipeline != null ? pipelineById.get(d.pipeline)?.name : undefined,
        d.stage != null ? stageById.get(d.stage)?.name : undefined,
    ], [companyById, pipelineById, stageById]);

    const {
        displayMode,
        setDisplayMode,
        query,
        setQuery,
        selectedIds,
        setSelectedIds,
        filteredItems: filteredDeals,
        selectedItems: selectedDeals,
        deleteDialogOpen,
        setDeleteDialogOpen,
    } = useRecordsBrowser<Deal>({
        items: deals,
        storageKey: 'deals:view',
        searchFields,
    });

    const [isDeleting, setIsDeleting] = useState(false);
    const [editSheetOpen, setEditSheetOpen] = useState(false);
    const [drafts, setDrafts] = useState<Record<number, DealDraft>>({});
    const [isSaving, setIsSaving] = useState(false);

    const emptyDraft: CreateDealPayload = {
        name: '',
        value: 0,
        actualValue: 0,
        currency: 'USD',
        pipeline: 0,
        stage: 0,
        company: null,
        expectedCloseDate: undefined,
    };
    const [newDialogOpen, setNewDialogOpen] = useState(false);
    const [isCreating, setIsCreating] = useState(false);
    const [newPayload, setNewPayload] = useState<CreateDealPayload>(emptyDraft);

    const closeNewDialog = (open: boolean) => {
        setNewDialogOpen(open);
        if (!open) setNewPayload(emptyDraft);
    };

    const createNewDeal = async () => {
        setIsCreating(true);
        try {
            await createDeal({
                ...newPayload,
                name: newPayload.name.trim(),
                value: Number.isFinite(newPayload.value) ? newPayload.value : 0,
                actualValue: Number.isFinite(newPayload.actualValue) ? newPayload.actualValue : 0,
                currency: newPayload.currency.trim() || 'USD',
                expectedCloseDate: newPayload.expectedCloseDate || undefined,
            });
            toast.success('Deal created', {
                style: { backgroundColor: 'var(--color-brand)', color: 'white' },
            });
            closeNewDialog(false);
            router.refresh();
        } catch (err) {
            console.error(err);
            toast.error(err instanceof Error ? err.message : 'Failed to create deal', {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
        } finally {
            setIsCreating(false);
        }
    };

    const openEditSheet = () => {
        const next: Record<number, DealDraft> = {};
        for (const d of selectedDeals) next[d.id] = toDraft(d);
        setDrafts(next);
        setEditSheetOpen(true);
    };

    const updateDraft = (id: number, patch: Partial<DealDraft>) => {
        setDrafts((prev) => ({ ...prev, [id]: { ...prev[id], ...patch } }));
    };

    const saveEdits = async () => {
        const changed = selectedDeals.filter((d) => {
            const draft = drafts[d.id];
            return draft && diffDraft(toDraft(d), draft);
        });

        if (changed.length === 0) {
            toast.info('No changes to save');
            setEditSheetOpen(false);
            return;
        }

        const invalid = changed.find((d) => {
            const draft = drafts[d.id];
            return !draft.name.trim() || !draft.pipeline || !draft.stage || !draft.currency.trim();
        });
        if (invalid) {
            toast.error(`Name, pipeline, stage and currency are required for "${invalid.name}"`);
            return;
        }

        setIsSaving(true);
        try {
            await Promise.all(
                changed.map((d) => {
                    const draft = drafts[d.id];
                    const payload: UpdateDealPayload = {
                        name: draft.name.trim(),
                        value: draft.value,
                        actualValue: draft.actualValue,
                        currency: draft.currency.trim(),
                        pipeline: draft.pipeline,
                        stage: draft.stage,
                        company: draft.company ?? null,
                        expectedCloseDate: draft.expectedCloseDate || undefined,
                        closedAt: draft.closedAt,
                    };
                    return updateDeal(d.id, payload);
                }),
            );
            toast.success(
                changed.length === 1 ? 'Deal updated' : `${changed.length} deals updated`,
                { style: { backgroundColor: 'var(--color-brand)', color: 'white' } },
            );
            setEditSheetOpen(false);
            router.refresh();
        } catch (err) {
            toast.error(err instanceof Error ? err.message : 'Failed to save', {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
        } finally {
            setIsSaving(false);
        }
    };

    const quickEditOne = useCallback((deal: Deal) => {
        setSelectedIds(new Set([deal.id]));
        setDrafts({ [deal.id]: toDraft(deal) });
        setEditSheetOpen(true);
    }, [setSelectedIds]);

    const deleteOne = useCallback((deal: Deal) => {
        setSelectedIds(new Set([deal.id]));
        setDeleteDialogOpen(true);
    }, [setSelectedIds, setDeleteDialogOpen]);

    const confirmDelete = async () => {
        if (selectedIds.size === 0) return;
        setIsDeleting(true);
        try {
            await Promise.all(Array.from(selectedIds).map((id) => deleteDeal(Number(id))));
            toast.success(
                selectedIds.size === 1 ? 'Deal deleted' : `${selectedIds.size} deals deleted`,
                { style: { backgroundColor: 'var(--color-brand)', color: 'white' } },
            );
            setSelectedIds(new Set());
            setDeleteDialogOpen(false);
            router.refresh();
        } catch (err) {
            toast.error(err instanceof Error ? err.message : 'Failed to delete', {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
        } finally {
            setIsDeleting(false);
        }
    };

    const viewSelected = () => {
        if (selectedDeals.length === 1) {
            router.push(`/records/deals/${selectedDeals[0].id}`);
        } else {
            selectedDeals.forEach((d) => window.open(`/records/deals/${d.id}`, '_blank'));
        }
    };

    const toggleDealStatus = useCallback(async (deal: Deal, closed: boolean) => {
        if (deal.pipeline == null || deal.stage == null) {
            toast.error('Cannot change status: deal is missing pipeline or stage');
            return;
        }
        try {
            await updateDeal(deal.id, {
                name: deal.name,
                value: deal.value,
                actualValue: deal.actualValue ?? 0,
                currency: deal.currency,
                pipeline: deal.pipeline,
                stage: deal.stage,
                company: deal.company ?? null,
                expectedCloseDate: deal.expectedCloseDate,
                closedAt: closed ? toMysqlDateTime(new Date().toISOString()) : null,
            });
            toast.success(closed ? 'Deal closed' : 'Deal reopened', {
                style: { backgroundColor: 'var(--color-brand)', color: 'white' },
            });
            router.refresh();
        } catch (err) {
            toast.error(err instanceof Error ? err.message : 'Failed to update status', {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
        }
    }, [router]);

    const summary = useMemo(() => {
        let openCount = 0;
        let openValue = 0;
        let closedActualValue = 0;
        let accuracyCount = 0;
        let accuracySum = 0;
        for (const d of deals) {
            if (isClosed(d)) {
                closedActualValue += d.actualValue ?? 0;
                if ((d.value ?? 0) > 0) {
                    accuracySum += (d.actualValue ?? 0) / d.value;
                    accuracyCount++;
                }
            } else {
                openCount++;
                openValue += d.value ?? 0;
            }
        }
        const forecastAccuracy = accuracyCount > 0 ? accuracySum / accuracyCount : null;
        return { openCount, openValue, closedActualValue, forecastAccuracy };
    }, [deals]);

    const columns: ColumnDef<Deal>[] = useMemo(() => [
        { key: 'name', label: 'Name', getSortValue: (d) => d.name ?? null },
        {
            key: 'value',
            label: 'Value',
            getSortValue: (d) => d.value ?? null,
            render: (d) => formatCompactCurrency(d.value ?? 0, d.currency || 'USD'),
        },
        {
            key: 'actualValue',
            label: 'Actual value',
            getSortValue: (d) => d.actualValue ?? null,
            render: (d) => formatCompactCurrency(d.actualValue ?? 0, d.currency || 'USD'),
        },
        {
            key: 'company',
            label: 'Company',
            getSortValue: (d) => (d.company != null ? companyById.get(d.company)?.name ?? null : null),
            render: (d) => (d.company != null ? <Link href={`/records/companies/${d.company}`} className="text-brand hover:text-brand-dark hover:underline transition-colors transition-duration-300 transition-ease-in-out">{companyById.get(d.company)?.name}</Link> : ''),
        },
        {
            key: 'pipeline',
            label: 'Pipeline',
            getSortValue: (d) => (d.pipeline != null ? pipelineById.get(d.pipeline)?.name ?? null : null),
            render: (d) => (d.pipeline != null ? pipelineById.get(d.pipeline)?.name : ''),
        },
        {
            key: 'stage',
            label: 'Stage',
            getSortValue: (d) => (d.stage != null ? stageById.get(d.stage)?.name ?? null : null),
            render: (d) => (d.stage != null ? stageById.get(d.stage)?.name : ''),
        },
        {
            key: 'expectedCloseDate',
            label: 'Expected close',
            getSortValue: (d) => (d.expectedCloseDate ? Date.parse(d.expectedCloseDate) : null),
            // render: (d) => formatShortDate(d.expectedCloseDate),
            render: (d) => formatDateTime(d.expectedCloseDate),
        },
        {
            key: 'status',
            label: 'Status',
            getSortValue: (d) => (isClosed(d) ? 1 : 0),
            render: (d) => {
                const closed = isClosed(d);
                return (
                    <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                            <button
                                type="button"
                                onClick={(e) => e.stopPropagation()}
                                className="inline-flex items-center gap-1 rounded-full bg-neutral-100 px-2 py-0.5 text-xs ring-1 ring-black/5 transition hover:bg-neutral-200"
                            >
                                <span className={closed ? 'text-red-500' : 'text-emerald-300'}>●</span>
                                {closed ? 'Closed' : 'Open'}
                                <ChevronDownIcon className="size-3 text-neutral-400" />
                            </button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="start" onClick={(e) => e.stopPropagation()}>
                            <DropdownMenuItem disabled={!closed} onSelect={() => toggleDealStatus(d, false)}>
                                <span className="text-emerald-300">●</span>
                                Mark open
                            </DropdownMenuItem>
                            <DropdownMenuItem disabled={closed} onSelect={() => toggleDealStatus(d, true)}>
                                <span className="text-red-500">●</span>
                                Mark closed
                            </DropdownMenuItem>
                        </DropdownMenuContent>
                    </DropdownMenu>
                );
            },
        },
        {
            key: 'updatedAt',
            label: 'Updated',
            getSortValue: (d) => (d.updatedAt ? Date.parse(d.updatedAt) : null),
            render: (d) => formatDateTime(d.updatedAt),
        },
    ], [companyById, pipelineById, stageById, toggleDealStatus]);

    return (
        <div className="space-y-6">
            <div className="flex items-center justify-between">
                <h1 className="text-4xl font-extrabold">Deals</h1>
                <Button className="bg-brand text-white" aria-label="Add deal" onClick={() => setNewDialogOpen(true)}>
                    <PlusIcon strokeWidth={2.5} />
                    New
                </Button>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <SummaryTile className="sm:col-span-2" label="Revenue trend" value={<DealsRevenueChart deals={deals} />} />
                <SummaryTile label="Stage ratio" value={<StageRatio deals={deals} />} />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-4 gap-3">
                <SummaryTile label="Projected pipeline" value={formatCompactCurrency(summary.openValue, 'USD')} />
                <SummaryTile label="Actual revenue" value={formatCompactCurrency(summary.closedActualValue, 'USD')} />
                <SummaryTile label="Open deals" value={String(summary.openCount)} />
                <SummaryTile
                    label="Forecast accuracy"
                    value={summary.forecastAccuracy != null ? `${Math.round(summary.forecastAccuracy * 100)}%` : '—'}
                />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <SummaryTile
                    className="sm:col-span-2"
                    label="Open deal aging"
                    value={<DealsAging deals={deals} stageById={stageById} />}
                />
                <SummaryTile label="Top deals" value={<TopDeals deals={deals} companyById={companyById} />} />
            </div>

            <div className="flex items-center gap-4">
                <button
                    type="button"
                    className="flex items-center gap-2 rounded-full bg-neutral-100 px-4 py-2 text-sm text-neutral-700 ring-1 ring-black/5 transition hover:bg-neutral-200"
                >
                    <FunnelIcon className="size-4 text-neutral-500" />
                    <ChevronDownIcon className="size-4 text-neutral-500" />
                </button>
                <div
                    role="group"
                    aria-label="Display mode"
                    className="inline-flex rounded-full bg-neutral-100 p-0.5 ring-1 ring-black/5"
                >
                    {/* <button
                        type="button"
                        onClick={() => setDisplayMode('grid')}
                        aria-label="Grid view"
                        aria-pressed={displayMode === 'grid'}
                        className={`flex h-7 w-7 items-center justify-center rounded-full transition ${displayMode === 'grid' ? 'bg-white text-neutral-900 shadow' : 'text-neutral-500 hover:text-neutral-700'}`}
                    >
                        <Squares2X2Icon className="size-4" />
                    </button> */}
                    <button
                        type="button"
                        onClick={() => setDisplayMode('table')}
                        aria-label="Table view"
                        aria-pressed={displayMode === 'table'}
                        className={`flex h-7 w-7 items-center justify-center rounded-full transition ${displayMode === 'table' ? 'bg-white text-neutral-900 shadow' : 'text-neutral-500 hover:text-neutral-700'}`}
                    >
                        <TableCellsIcon className="size-4" />
                    </button>
                </div>

                {selectedIds.size > 0 && (
                    <div className="flex items-center gap-2">
                        <span className="text-sm text-neutral-500">{selectedIds.size} selected</span>
                        <ButtonGroup className="rounded-full bg-neutral-100">
                            <Button variant="outline" size="sm" onClick={viewSelected}>
                                <EyeIcon className="size-4" />
                                View
                            </Button>
                            <Button variant="outline" size="sm" onClick={openEditSheet}>
                                <PencilIcon className="size-4" />
                                Quick edit
                            </Button>
                            <DropdownMenu>
                                <DropdownMenuTrigger asChild>
                                    <Button variant="outline" size="sm">
                                        <EllipsisVerticalIcon className="size-4" />
                                    </Button>
                                </DropdownMenuTrigger>
                                <DropdownMenuContent>
                                    <DropdownMenuItem
                                        variant="destructive"
                                        onSelect={(e) => {
                                            e.preventDefault();
                                            setDeleteDialogOpen(true);
                                        }}
                                    >
                                        <TrashIcon />
                                        Delete
                                    </DropdownMenuItem>
                                </DropdownMenuContent>
                            </DropdownMenu>
                        </ButtonGroup>
                    </div>
                )}

                <div className="relative ml-auto w-full max-w-sm">
                    <input
                        type="text"
                        placeholder="Search deals"
                        value={query}
                        onChange={(e) => setQuery(e.target.value)}
                        className="w-full rounded-full bg-neutral-100 px-4 py-2 pr-10 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand"
                    />
                    <MagnifyingGlassIcon className="pointer-events-none absolute right-3 top-1/2 size-4 -translate-y-1/2 text-neutral-500" />
                </div>
            </div>

            <RecordsRenderView<Deal>
                data={filteredDeals}
                columns={columns}
                renderCard={(item, { onQuickEdit, onDelete }) => (
                    <DealCard
                        deal={item}
                        company={item.company != null ? companyById.get(item.company) : undefined}
                        pipeline={item.pipeline != null ? pipelineById.get(item.pipeline) : undefined}
                        stage={item.stage != null ? stageById.get(item.stage) : undefined}
                        onQuickEdit={onQuickEdit ? () => onQuickEdit(item) : undefined}
                        onDelete={onDelete ? () => onDelete(item) : undefined}
                    />
                )}
                renderAvatar={(item) => {
                    const company = item.company != null ? companyById.get(item.company) : undefined;
                    if (company) return <CompanyAvatar company={company} type="large" />;
                    const contact = contactByDealId.get(item.id);
                    return (
                        <ContactAvatar
                            contact={contact ?? { id: 0, name: 'Freelancer', imageUrl: '', email: '', phone: '', title: '', createdAt: '', updatedAt: '' }}
                            type="large"
                        />
                    );
                }}
                detailPath={(item) => `/records/deals/${item.id}`}
                displayMode={displayMode}   
                selectedIds={selectedIds}
                onSelectedIdsChange={setSelectedIds}
                onQuickEdit={quickEditOne}
                onDelete={deleteOne}
                gridClassName="grid grid-cols-1 gap-3 pt-8"
                entityLabel="deal"
            />

            <QuickEditDealSheet
                open={editSheetOpen}
                onOpenChange={setEditSheetOpen}
                selectedIds={selectedIds}
                selectedDeals={selectedDeals}
                drafts={drafts}
                updateDraft={updateDraft}
                companies={companies}
                pipelines={pipelines}
                stagesByPipeline={stagesByPipeline}
                isSaving={isSaving}
                saveEdits={saveEdits}
            />

            <NewDealDialog
                open={newDialogOpen}
                onOpenChange={closeNewDialog}
                payload={newPayload}
                setPayload={setNewPayload}
                companies={companies}
                pipelines={pipelines}
                stagesByPipeline={stagesByPipeline}
                isCreating={isCreating}
                createNewDeal={createNewDeal}
            />

            <DeleteRecordDialog
                open={deleteDialogOpen}
                onOpenChange={setDeleteDialogOpen}
                selectedIds={selectedIds}
                selectedItems={selectedDeals}
                entityLabel="deal"
                getDisplayName={(d) => d.name}
                isDeleting={isDeleting}
                confirmDelete={confirmDelete}
            />
        </div>
    );
}