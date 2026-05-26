'use client';

import { useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { ChevronRightIcon } from '@heroicons/react/24/solid';
import {
    EllipsisHorizontalIcon,
    EyeIcon,
    PencilIcon,
    TrashIcon,
} from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu';
import {
    AvatarFallback,
    Avatar,
    AvatarGroup,
    AvatarGroupCount,
    AvatarImage,
} from '@/components/ui/avatar';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import type { LoadStatus, Pipeline, PipelineMetrics, Stage, User } from '@/app/lib/types';
import Chip from '@/app/components/Chip';
import Link from 'next/link';
import { cn } from '@/lib/utils';

interface PipelineCardProps {
    pipeline: Pipeline;
    metrics?: PipelineMetrics;
    metricsStatus: LoadStatus;
    onFirstExpand?: () => void;
    onQuickEdit?: () => void;
    onDelete?: () => void;
}

export default function PipelineCard({
    pipeline,
    metrics,
    metricsStatus,
    onFirstExpand,
    onQuickEdit,
    onDelete,
}: PipelineCardProps) {
    const router = useRouter();
    const [isExpanded, setIsExpanded] = useState(false);

    const open = () => router.push(`/records/pipelines/${pipeline.id}`);
    const toggleExpand = () => {
        if (!isExpanded) onFirstExpand?.();
        setIsExpanded((prev) => !prev);
    };

    const stages = useMemo<Stage[]>(() => {
        if (!metrics) return [];
        return metrics.stages
            .map((sm) => sm.stage)
            .sort((a, b) => a.position - b.position);
    }, [metrics]);

    return (
        <div className="rounded-2xl bg-neutral-100 ring-1 ring-black/5 transition">
            <div
                className="flex items-center gap-4 p-4 cursor-pointer hover:bg-neutral-200 rounded-2xl"
                onClick={toggleExpand}
            >
                <div className="flex-1 min-w-0">
                    <h3 className="text-base font-semibold text-neutral-900 truncate">
                        {pipeline.name}
                    </h3>
                </div>

                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <button
                            type="button"
                            aria-label="Pipeline actions"
                            onClick={(e) => e.stopPropagation()}
                            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-neutral-200 text-neutral-700 transition hover:bg-neutral-300"
                        >
                            <EllipsisHorizontalIcon className="size-4" />
                        </button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent
                        align="end"
                        side="bottom"
                        className="w-44"
                        onClick={(e) => e.stopPropagation()}
                    >
                        {/* <DropdownMenuItem onSelect={open}>
                            <EyeIcon className="size-4 text-neutral-500" />
                            View
                        </DropdownMenuItem> */}
                        {onQuickEdit && (
                            <DropdownMenuItem
                                onSelect={(e) => {
                                    e.preventDefault();
                                    onQuickEdit();
                                }}
                            >
                                <PencilIcon className="size-4 text-neutral-500" />
                                Quick edit
                            </DropdownMenuItem>
                        )}
                        {onDelete && (
                            <>
                                <DropdownMenuSeparator />
                                <DropdownMenuItem
                                    className="text-destructive hover:bg-red-500/10"
                                    onSelect={(e) => {
                                        e.preventDefault();
                                        onDelete();
                                    }}
                                >
                                    <TrashIcon className="size-4 text-destructive" />
                                    Delete
                                </DropdownMenuItem>
                            </>
                        )}
                    </DropdownMenuContent>
                </DropdownMenu>

                {/* <Button
                    variant="outline"
                    size="sm"
                    onClick={(e) => {
                        e.stopPropagation();
                        open();
                    }}
                    aria-label="Open pipeline page"
                    className="w-12 h-12 shrink-0 bg-neutral-200 hover:bg-neutral-300 outline-none border-none shadow-none"
                >
                    <ChevronRightIcon className="size-4" />
                </Button> */}
            </div>

            {isExpanded && (
                <div className="border-t border-black/10 p-4 space-y-4">
                    {metricsStatus === 'loading' && (
                        <div className="flex items-center justify-center py-4 text-sm text-neutral-500">
                            <Loader2Icon className="size-4 animate-spin mr-2" />
                            Loading metrics…
                        </div>
                    )}

                    {metricsStatus === 'error' && (
                        <div className="flex items-center justify-between py-2 text-sm">
                            <span className="text-destructive">Failed to load metrics.</span>
                            <Button
                                variant="outline"
                                size="sm"
                                onClick={(e) => {
                                    e.stopPropagation();
                                    onFirstExpand?.();
                                }}
                            >
                                Retry
                            </Button>
                        </div>
                    )}

                    {metricsStatus === 'ready' && metrics && (
                        <div className="flex flex-wrap items-start gap-8">
                            <CountTile label="Stages" value={metrics.numStages} />
                            <Link href={`/records/deals?pipelineId=${pipeline.id}`} className=""><CountTile className="hover:text-brand transition-colors duration-300 transition-ease-in-out" label="Deals" value={metrics.numDeals} /></Link>
                            <RelatedUsersSection users={metrics.relatedUsers} />
                            <AssociatedStagesSection stages={stages} />
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}

function CountTile({ label, value, className }: { label: string; value: number; className?: string }) {
    return (
        <div>
            <p className="text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase mb-2">
                {label}
            </p>
            <p className={cn("text-2xl font-semibold text-neutral-900", className)}>{value}</p>
        </div>
    );
}

function RelatedUsersSection({ users }: { users: User[] }) {
    const router = useRouter();
    const visible = users.slice(0, 5);
    const overflow = users.length - visible.length;

    return (
        <div>
            <p className="text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase mb-2">
                Relations ({users.length})
            </p>
            {users.length === 0 ? (
                <p className="text-xs text-neutral-400">No related users</p>
            ) : (
                <AvatarGroup>
                    {visible.map((u) => {
                        const fallback = (u.displayName ?? '?').charAt(0);
                        return (
                            <Tooltip key={u.id}>
                                <TooltipTrigger asChild>
                                    <Avatar
                                        className="border-white w-10 h-10 cursor-pointer hover:scale-110 transition-all duration-300 bg-white"
                                        onClick={() => router.push(`/users/${u.id}`)}
                                    >
                                        <AvatarImage src={u.profilePictureUrl} className="w-10 h-10" />
                                        <AvatarFallback className="w-10 h-10">{fallback}</AvatarFallback>
                                    </Avatar>
                                </TooltipTrigger>
                                <TooltipContent side="bottom" align="center">
                                    {u.displayName ?? '?'}
                                </TooltipContent>
                            </Tooltip>
                        );
                    })}
                    {overflow > 0 && (
                        <AvatarGroupCount className="ring-transparent bg-neutral-200 text-neutral-600 w-10 h-10">
                            +{overflow}
                        </AvatarGroupCount>
                    )}
                </AvatarGroup>
            )}
        </div>
    );
}

function AssociatedStagesSection({ stages }: { stages: Stage[] }) {
    return (
        <div>
            <p className="text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase mb-2">
                Associated stages ({stages.length})
            </p>
            <div className="flex flex-wrap items-start gap-2">
                {stages.map((s) => (
                    // <div key={s.id}>{s.name}</div>
                    <Chip key={s.id} type="default" color="bg-brand-light text-brand-dark">{s.name}</Chip>
                ))}
            </div>
        </div>
    );
}