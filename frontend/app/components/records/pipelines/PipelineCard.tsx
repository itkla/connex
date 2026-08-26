'use client';

import { useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
    EllipsisHorizontalIcon,
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
import { pipelineDealsHref } from '@/app/components/records/deals/dealLinks';
import Chip from '@/app/components/Chip';
import Link from 'next/link';
import { cn } from '@/lib/utils';
import { useTranslations } from 'next-intl';

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
    const t = useTranslations('PipelinesCard');
    const [isExpanded, setIsExpanded] = useState(false);

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
        <div className="rounded-2xl border border-border bg-muted transition">
            <div
                className="flex items-center gap-4 p-4 cursor-pointer hover:bg-muted/60 rounded-2xl"
                onClick={toggleExpand}
            >
                <div className="flex-1 min-w-0">
                    <h3 className="text-base font-semibold text-foreground truncate">
                        {pipeline.name}
                    </h3>
                </div>

                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <button
                            type="button"
                            aria-label={t('actionsAriaLabel')}
                            onClick={(e) => e.stopPropagation()}
                            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-background text-foreground transition hover:bg-background/60"
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
                        {onQuickEdit && (
                            <DropdownMenuItem
                                onSelect={(e) => {
                                    e.preventDefault();
                                    onQuickEdit();
                                }}
                            >
                                <PencilIcon className="size-4 text-muted-foreground" />
                                {t('quickEdit')}
                            </DropdownMenuItem>
                        )}
                        {onDelete && (
                            <>
                                <DropdownMenuSeparator />
                                <DropdownMenuItem
                                    className="text-destructive hover:bg-destructive/10"
                                    onSelect={(e) => {
                                        e.preventDefault();
                                        onDelete();
                                    }}
                                >
                                    <TrashIcon className="size-4 text-destructive" />
                                    {t('delete')}
                                </DropdownMenuItem>
                            </>
                        )}
                    </DropdownMenuContent>
                </DropdownMenu>
            </div>

            {isExpanded && (
                <div className="border-t border-border p-4 space-y-4">
                    {metricsStatus === 'loading' && (
                        <div className="flex items-center justify-center py-4 text-sm text-muted-foreground">
                            <Loader2Icon className="size-4 animate-spin mr-2" />
                            {t('loadingMetrics')}
                        </div>
                    )}

                    {metricsStatus === 'error' && (
                        <div className="flex items-center justify-between py-2 text-sm">
                            <span className="text-destructive">{t('failedToLoadMetrics')}</span>
                            <Button
                                variant="outline"
                                size="sm"
                                onClick={(e) => {
                                    e.stopPropagation();
                                    onFirstExpand?.();
                                }}
                            >
                                {t('retry')}
                            </Button>
                        </div>
                    )}

                    {metricsStatus === 'ready' && metrics && (
                        <div className="flex flex-wrap items-start gap-8">
                            <CountTile label={t('stages')} value={metrics.numStages} />
                            <Link href={pipelineDealsHref(pipeline.id)} className=""><CountTile className="hover:text-brand transition-colors duration-300 transition-ease-in-out" label={t('deals')} value={metrics.numDeals} /></Link>
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
            <p className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase mb-2">
                {label}
            </p>
            <p className={cn("text-2xl font-semibold text-foreground", className)}>{value}</p>
        </div>
    );
}

function RelatedUsersSection({ users }: { users: User[] }) {
    const router = useRouter();
    const t = useTranslations('PipelinesCard');
    const visible = users.slice(0, 5);
    const overflow = users.length - visible.length;

    return (
        <div>
            <p className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase mb-2">
                {t('relations', { count: users.length })}
            </p>
            {users.length === 0 ? (
                <p className="text-xs text-muted-foreground">{t('noRelatedUsers')}</p>
            ) : (
                <AvatarGroup>
                    {visible.map((u) => {
                        const fallback = (u.displayName ?? t('unknownUser')).charAt(0);
                        return (
                            <Tooltip key={u.id}>
                                <TooltipTrigger asChild>
                                    <Avatar
                                        className="border-background w-10 h-10 cursor-pointer hover:scale-110 transition-all duration-300 bg-muted"
                                        onClick={() => router.push(`/users/${u.id}`)}
                                    >
                                        <AvatarImage src={u.profilePictureUrl} className="w-10 h-10" />
                                        <AvatarFallback className="w-10 h-10">{fallback}</AvatarFallback>
                                    </Avatar>
                                </TooltipTrigger>
                                <TooltipContent side="bottom" align="center">
                                    {u.displayName ?? t('unknownUser')}
                                </TooltipContent>
                            </Tooltip>
                        );
                    })}
                    {overflow > 0 && (
                        <AvatarGroupCount className="ring-transparent bg-muted text-muted-foreground w-10 h-10">
                            +{overflow}
                        </AvatarGroupCount>
                    )}
                </AvatarGroup>
            )}
        </div>
    );
}

function AssociatedStagesSection({ stages }: { stages: Stage[] }) {
    const t = useTranslations('PipelinesCard');
    return (
        <div>
            <p className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase mb-2">
                {t('associatedStages', { count: stages.length })}
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