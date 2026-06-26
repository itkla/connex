'use client';

import { Handle, Position, useReactFlow, type NodeProps } from '@xyflow/react';
import { memo } from 'react';
import { motion, useReducedMotion } from 'motion/react';
import Link from 'next/link';
import { ChevronRightIcon, GlobeAltIcon, PhoneIcon } from '@heroicons/react/24/outline';
import CompanyAvatar from '@/app/components/records/companies/CompanyAvatar';
import { EngagementSparkline, RevenueTiles } from '@/app/components/records/companies/CompanyCard';
import { Avatar, AvatarFallback, AvatarGroup, AvatarImage } from '@/components/ui/avatar';
import { buttonVariants } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { warmthDotClass } from '@/app/lib/utils';
import type { CompanyNode as CompanyNodeType } from './graph/types';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import NodeDot from '@/app/components/map/NodeDot';
import { useDotEnabled } from '@/app/hooks/useNodeTier';

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

type Person = { id: number; src?: string; label: string };

function StatStrip({ items }: { items: { label: string; value: number }[] }) {
    return (
        <div className="mt-3 grid grid-cols-4 divide-x divide-border overflow-hidden rounded-xl ring-1 ring-border">
            {items.map((s) => (
                <div key={s.label} className="px-2 py-2 text-center">
                    <p className="text-sm font-semibold tabular-nums text-foreground">{s.value}</p>
                    <p className="mt-0.5 text-[10px] font-medium uppercase tracking-wider text-muted-foreground">
                        {s.label}
                    </p>
                </div>
            ))}
        </div>
    );
}

function CompanyNodeImpl({ id, data }: NodeProps<CompanyNodeType>) {
    const { updateNodeData } = useReactFlow();
    const { company, metrics, warmth, expanded, hovered } = data;
    const dotEnabled = useDotEnabled();
    const reduceMotion = useReducedMotion();
    const toggle = () => updateNodeData(id, { expanded: !expanded });

    if (dotEnabled && !expanded && !hovered) {
        return (
            <NodeDot
                shape="square"
                className={warmth ? warmthDotClass(warmth) : 'bg-muted-foreground/50'}
                title={company.name}
                onClick={toggle}
            />
        );
    }

    if (!expanded) {
        return (
            <div className="map-node-bloom relative flex flex-col items-center">
                <Handle type="target" position={Position.Top} isConnectable={false} className="!opacity-0" />
                <Handle type="source" position={Position.Bottom} isConnectable={false} className="!opacity-0" />
                <button type="button" onClick={toggle} className="rounded-2xl transition-transform hover:scale-110" title={company.name}>
                    <CompanyAvatar company={company} type="large" />
                </button>
                <span className="map-node-label pointer-events-none absolute left-1/2 top-full mt-1 -translate-x-1/2 max-w-[9rem] truncate text-center text-xs font-medium text-foreground">
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
        <motion.div
            initial={reduceMotion ? { opacity: 0 } : { opacity: 0, scale: 0.96, y: 2 }}
            animate={reduceMotion ? { opacity: 1 } : { opacity: 1, scale: 1, y: 0 }}
            transition={{ duration: reduceMotion ? 0.12 : 0.18, ease: EASE_OUT }}
            className="relative z-10 w-96 rounded-2xl bg-card p-4 ring-1 ring-border shadow-[0_10px_30px_-12px_rgb(0_0_0/0.20)] dark:shadow-[0_18px_45px_-18px_rgb(0_0_0/0.65)]"
        >
            <Handle type="target" position={Position.Top} isConnectable={false} className="!opacity-0" />
            <Handle type="source" position={Position.Bottom} isConnectable={false} className="!opacity-0" />

            <div className="flex items-center gap-2">
                <button type="button" onClick={toggle} className="flex min-w-0 flex-1 items-center gap-3 text-left">
                    <CompanyAvatar company={company} type="large" />
                    <div className="min-w-0">
                        <p className="truncate text-sm font-semibold text-foreground">{company.name}</p>
                        {company.industry ? <p className="truncate text-xs text-muted-foreground">{company.industry}</p> : null}
                    </div>
                </button>
                <Tooltip>
                    <TooltipTrigger asChild>
                        <Link
                            href={`/records/companies/${company.id}`}
                            aria-label="Open company record"
                            className={cn(
                                buttonVariants({ variant: 'ghost', size: 'icon-sm' }),
                                'nodrag group/open shrink-0 rounded-full bg-muted text-muted-foreground shadow-none hover:bg-muted/80 hover:text-foreground',
                            )}
                        >
                            <ChevronRightIcon className="size-4 transition-transform duration-150 ease-out group-hover/open:translate-x-0.5" />
                        </Link>
                    </TooltipTrigger>
                    <TooltipContent side="left" align="center">
                        View company
                    </TooltipContent>
                </Tooltip>
            </div>

            {(company.website || company.phone) && (
                <div className="mt-3 space-y-0.5 border-t border-border pt-3 text-xs text-muted-foreground">
                    {company.website ? (
                        <p className="flex items-center gap-2">
                            <GlobeAltIcon className="size-3.5 shrink-0 text-muted-foreground" />
                            <Link
                                href={company.website}
                                target="_blank"
                                className="nodrag truncate transition-colors hover:text-brand-dark"
                            >
                                {company.website}
                            </Link>
                        </p>
                    ) : null}
                    {company.phone ? (
                        <p className="flex items-center gap-2">
                            <PhoneIcon className="size-3.5 shrink-0 text-muted-foreground" />
                            <span className="truncate tabular-nums">{company.phone}</span>
                        </p>
                    ) : null}
                </div>
            )}

            <StatStrip
                items={[
                    { label: 'Deals', value: metrics.numDeals },
                    { label: 'People', value: metrics.persons.length },
                    { label: 'Activity', value: metrics.numActivities },
                    { label: 'Notes', value: metrics.numNotes },
                ]}
            />

            <div className="mt-3 space-y-2">
                <RevenueTiles pastRevenue={metrics.pastRevenue} projectedRevenue={metrics.projectedRevenue} currency={metrics.currency} />
                <div className="nodrag nowheel">
                    <EngagementSparkline data={metrics.weeklyEngagement} />
                </div>
            </div>

            {(employees.length > 0 || relations.length > 0) && (
                <div className="mt-3 flex flex-wrap items-start gap-x-6 gap-y-2 border-t border-border pt-3">
                    {employees.length > 0 && (
                        <div>
                            <p className="mb-1.5 text-[10px] font-medium uppercase tracking-[0.12em] text-muted-foreground">
                                People · {employees.length}
                            </p>
                            <AvatarGroup>
                                {employees.map((e) => (
                                    <Tooltip key={e.id}>
                                        <TooltipTrigger asChild>
                                            <Link href={`/records/contacts/${e.id}`} className="nodrag transition-transform hover:scale-110">
                                                <Avatar key={e.id} className="h-7 w-7 bg-card">
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
                            </AvatarGroup>
                        </div>
                    )}
                    {relations.length > 0 && (
                        <div>
                            <p className="mb-1.5 text-[10px] font-medium uppercase tracking-[0.12em] text-muted-foreground">
                                Relations · {relations.length}
                            </p>
                            <AvatarGroup>
                                {relations.map((r) => (
                                    <Tooltip key={r.id}>
                                        <TooltipTrigger asChild>
                                            <Link href={`/users/${r.id}`} className="nodrag transition-transform hover:scale-110">
                                                <Avatar key={r.id} className="h-7 w-7 bg-card">
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
        </motion.div>
    );
}

export default memo(CompanyNodeImpl);