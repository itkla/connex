'use client';

import { Handle, Position, useReactFlow, type NodeProps } from '@xyflow/react';
import { memo } from 'react';
import Link from 'next/link';
import { ArrowUpRightIcon, ChevronRightIcon, GlobeAltIcon, PhoneIcon } from '@heroicons/react/24/outline';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import { EngagementSparkline, RevenueTiles } from '@/app/components/records/companies/CompanyCard';
import { Avatar, AvatarFallback, AvatarGroup, AvatarGroupCount, AvatarImage } from '@/components/ui/avatar';
import { Button } from '@/components/ui/button';
import type { CompanyNode as CompanyNodeType } from './graph/types';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

type Person = { id: number; src?: string; label: string };

function Stat({ label, value }: { label: string; value: number }) {
    return (
        <div className="rounded-lg bg-neutral-50 px-2 py-1.5 text-center ring-1 ring-black/5">
            <p className="text-sm font-semibold text-neutral-900">{value}</p>
            <p className="text-[10px] uppercase tracking-wider text-neutral-500">{label}</p>
        </div>
    );
}

function CompanyNodeImpl({ id, data }: NodeProps<CompanyNodeType>) {
    const { updateNodeData } = useReactFlow();
    const { company, metrics, expanded } = data;
    const toggle = () => updateNodeData(id, { expanded: !expanded });

    if (!expanded) {
        return (
            <div className="relative flex flex-col items-center">
                <Handle type="target" position={Position.Top} isConnectable={false} className="!opacity-0" />
                <Handle type="source" position={Position.Bottom} isConnectable={false} className="!opacity-0" />
                <button type="button" onClick={toggle} className="rounded-2xl transition-transform hover:scale-110" title={company.name}>
                    <CompanyAvatar company={company} type="large" />
                </button>
                <span className="pointer-events-none absolute left-1/2 top-full mt-1 -translate-x-1/2 max-w-[9rem] truncate text-center text-xs font-medium text-neutral-700">
                    {company.name}
                </span>
            </div>
        );
    }

    const employees: Person[] = metrics.persons.map((p) => ({ id: p.id, src: p.imageUrl, label: p.name }));
    const relations: Person[] = metrics.relatedUsers.map((u) => ({
        id: u.id,
        src: u.profilePictureUrl,
        label: u.displayName || u.username,
    }));

    return (
        <div className="relative w-96 rounded-2xl border border-neutral-200 bg-white p-4 shadow-xl z-10">
            <Handle type="target" position={Position.Top} isConnectable={false} className="!opacity-0" />
            <Handle type="source" position={Position.Bottom} isConnectable={false} className="!opacity-0" />

            <div className="flex items-center gap-3 justify-between">
                <button type="button" onClick={toggle} className="flex min-w-0 flex-1 items-center gap-3 text-left">
                    <CompanyAvatar company={company} type="large" />
                    <div className="min-w-0">
                        <p className="truncate text-sm font-semibold text-neutral-900">{company.name}</p>
                        {company.industry ? <p className="truncate text-xs text-neutral-500">{company.industry}</p> : null}
                    </div>
                </button>
                <div className="flex items-center">
                    <Tooltip>
                        <TooltipTrigger asChild>
                            <Link href={`/records/companies/${company.id}`} className="nodrag shrink-0 flex items-center">
                                <Button variant="outline" size="icon-lg" aria-label="Open company record" className="flex items-center justify-center bg-neutral-100 shadow-none hover:bg-neutral-200">
                                    {/* <ArrowUpRightIcon className="size-3.5 text-neutral-500" /> */}
                                    <ChevronRightIcon className="size-3.5 text-neutral-500" />
                                </Button>
                            </Link>
                        </TooltipTrigger>
                        <TooltipContent side="left" align="center">
                            View Company
                        </TooltipContent>
                    </Tooltip>

                </div>
            </div>

            {(company.website || company.phone) && (
                <div className="mt-3 space-y-1.5 text-xs text-neutral-600">
                    {company.website ? (
                        <p className="flex items-center gap-1.5">
                            <GlobeAltIcon className="size-3.5 shrink-0 text-neutral-400" />
                            <Link href={company.website} target="_blank" className="nodrag truncate transition hover:text-brand">
                                {company.website}
                            </Link>
                        </p>
                    ) : null}
                    {company.phone ? (
                        <p className="flex items-center gap-1.5">
                            <PhoneIcon className="size-3.5 shrink-0 text-neutral-400" />
                            <span className="truncate">{company.phone}</span>
                        </p>
                    ) : null}
                </div>
            )}

            <div className="mt-3 grid grid-cols-4 gap-1.5">
                {/* // TODO: make these link out to the respective records */}
                <Stat label="Deals" value={metrics.numDeals} />
                <Stat label="People" value={metrics.persons.length} />
                <Stat label="Activity" value={metrics.numActivities} />
                <Stat label="Notes" value={metrics.numNotes} />
            </div>

            <div className="mt-3 space-y-2">
                <RevenueTiles pastRevenue={metrics.pastRevenue} projectedRevenue={metrics.projectedRevenue} currency={metrics.currency} />
                <div className="nodrag nowheel">
                    <EngagementSparkline data={metrics.weeklyEngagement} />
                </div>
            </div>

            {(employees.length > 0 || relations.length > 0) && (
                <div className="mt-3 flex flex-wrap items-start gap-x-6 gap-y-2">
                    {employees.length > 0 && (
                        <div>
                            <p className="mb-1 text-[10px] font-medium uppercase tracking-wider text-neutral-500">
                                People · {employees.length}
                            </p>
                            {/* <MiniAvatars people={employees} /> */}
                            <AvatarGroup>
                                {employees.map((e) => (
                                    <Tooltip key={e.id}>
                                        <TooltipTrigger asChild>
                                            <Link href={`/records/contacts/${e.id}`} className="nodrag">
                                                <Avatar key={e.id} className="h-7 w-7 bg-white">
                                                    <AvatarImage src={e.src} />
                                                    <AvatarFallback className="text-[10px]">{(e.label || '?').charAt(0)}</AvatarFallback>
                                                </Avatar>
                                            </Link>
                                        </TooltipTrigger>
                                        <TooltipContent side="bottom" align="center">
                                            {e.label}
                                        </TooltipContent>
                                    </Tooltip>
                                ))}
                                {/* {overflow > 0 && (
                                    <AvatarGroupCount className="h-7 w-7 bg-neutral-200 text-[10px] text-neutral-600 ring-transparent">
                                        +{overflow}
                                    </AvatarGroupCount>
                                )}   */}
                            </AvatarGroup>
                        </div>
                    )}
                    {relations.length > 0 && (
                        <div>
                            <p className="mb-1 text-[10px] font-medium uppercase tracking-wider text-neutral-500">
                                Relations · {relations.length}
                            </p>
                            {/* <MiniAvatars people={relations} /> */}
                            <AvatarGroup>
                                {relations.map((r) => (
                                    <Tooltip key={r.id}>
                                        <TooltipTrigger asChild>
                                            <Link href={`/users/${r.id}`} className="nodrag">
                                                <Avatar key={r.id} className="h-7 w-7 bg-white">
                                                    <AvatarImage src={r.src} />
                                                    <AvatarFallback className="text-[10px]">{(r.label || '?').charAt(0)}</AvatarFallback>
                                                </Avatar>
                                            </Link>
                                        </TooltipTrigger>
                                        <TooltipContent side="bottom" align="center">
                                            {r.label}
                                        </TooltipContent>
                                    </Tooltip>
                                ))}
                            </AvatarGroup>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}

export default memo(CompanyNodeImpl);