'use client';

import {
    BaseEdge,
    EdgeLabelRenderer,
    getStraightPath,
    useInternalNode,
    type EdgeProps,
} from '@xyflow/react';
import { useState } from 'react';
import { cn } from '@/lib/utils';
import type { DealSummary, RelationEdge as RelationEdgeType } from '../graph/types';

type Circle = { x: number; y: number; r: number };

function circleOf(node: ReturnType<typeof useInternalNode>): Circle | null {
    if (!node) return null;
    const w = node.measured?.width ?? 0;
    const h = node.measured?.height ?? 0;
    return {
        x: node.internals.positionAbsolute.x + w / 2,
        y: node.internals.positionAbsolute.y + h / 2,
        r: Math.min(w, h) / 2, // inscribed radius so the line meets the avatar edge
    };
}

function boundaryPoint(from: Circle, to: Circle): { x: number; y: number } {
    const dx = to.x - from.x;
    const dy = to.y - from.y;
    const dist = Math.hypot(dx, dy) || 1;
    return { x: from.x + (dx / dist) * from.r, y: from.y + (dy / dist) * from.r };
}

const OUTCOME_BADGE: Record<DealSummary['outcome'], string> = {
    won: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300',
    lost: 'bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-300',
    open: 'bg-blue-100 text-blue-700 dark:bg-blue-950/40 dark:text-blue-300',
    closed: 'bg-muted text-muted-foreground',
};

export default function RelationEdge({ id, source, target, data, markerEnd }: EdgeProps<RelationEdgeType>) {
    const sourceNode = useInternalNode(source);
    const targetNode = useInternalNode(target);
    const [hovered, setHovered] = useState(false);

    const s = circleOf(sourceNode);
    const t = circleOf(targetNode);
    if (!s || !t) return null;

    const sp = boundaryPoint(s, t);
    const tp = boundaryPoint(t, s);
    const [path, labelX, labelY] = getStraightPath({
        sourceX: sp.x,
        sourceY: sp.y,
        targetX: tp.x,
        targetY: tp.y,
    });

    const variant = data?.variant ?? 'rel-cc';
    const isRelCc = variant === 'rel-cc'; // the deal-status gradient edge
    const deals = data?.deals ?? [];
    const gradId = `relgrad-${id}`;

    const stroke = isRelCc ? `url(#${gradId})` : variant === 'uc-user' ? 'var(--muted-foreground)' : 'var(--border)';
    const strokeWidth = isRelCc ? 2.5 : variant === 'uc-user' ? 1.75 : 1.25;

    return (
        <>
            {isRelCc && (
                <defs>
                    <linearGradient id={gradId} gradientUnits="userSpaceOnUse" x1={sp.x} y1={sp.y} x2={tp.x} y2={tp.y}>
                        <stop offset="0%" stopColor={data?.ucColor ?? '#3b82f6'} />
                        <stop offset="100%" stopColor={data?.ccColor ?? '#3b82f6'} />
                    </linearGradient>
                </defs>
            )}

            <BaseEdge
                id={id}
                path={path}
                markerEnd={markerEnd}
                style={{
                    stroke,
                    strokeWidth,
                    strokeDasharray: data?.dashed ? '6 5' : undefined,
                }}
            />

            <path
                d={path}
                fill="none"
                stroke="transparent"
                strokeWidth={16}
                style={{ pointerEvents: 'stroke', cursor: deals.length ? 'pointer' : 'default' }}
                onMouseEnter={() => setHovered(true)}
                onMouseLeave={() => setHovered(false)}
            />

            {hovered && deals.length > 0 && (
                <EdgeLabelRenderer>
                    <div
                        className="nodrag nopan pointer-events-none absolute z-50 w-60 rounded-lg border border-border bg-popover text-popover-foreground p-2 shadow-xl"
                        style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}
                    >
                        <p className="mb-1.5 px-1 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                            {deals.length} deal{deals.length > 1 ? 's' : ''}
                        </p>
                        <ul className="space-y-1">
                            {deals.map((d) => (
                                <li key={d.id} className="flex items-center justify-between gap-2 rounded px-1 py-0.5">
                                    <span className="min-w-0 flex-1 truncate text-xs text-foreground">{d.name}</span>
                                    <span
                                        className={cn(
                                            'shrink-0 rounded-full px-1.5 py-0.5 text-[9px] font-medium uppercase',
                                            OUTCOME_BADGE[d.outcome],
                                        )}
                                    >
                                        {d.outcome}
                                    </span>
                                </li>
                            ))}
                        </ul>
                    </div>
                </EdgeLabelRenderer>
            )}
        </>
    );
}