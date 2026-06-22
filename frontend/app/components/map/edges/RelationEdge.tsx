'use client';

import {
    BaseEdge,
    EdgeLabelRenderer,
    getStraightPath,
    useStore,
    type EdgeProps,
    type ReactFlowState,
} from '@xyflow/react';
import { useCallback, useState } from 'react';
import { cn } from '@/lib/utils';
import type { DealSummary, RelationEdge as RelationEdgeType } from '../graph/types';

type Geo = { sx: number; sy: number; sr: number; tx: number; ty: number; tr: number };

function geoEqual(a: Geo | null, b: Geo | null) {
    if (a === b) return true;
    if (!a || !b) return false;
    return (
        a.sx === b.sx && a.sy === b.sy && a.sr === b.sr &&
        a.tx === b.tx && a.ty === b.ty && a.tr === b.tr
    );
}

function boundaryPoint(fx: number, fy: number, r: number, tx: number, ty: number) {
    const dx = tx - fx;
    const dy = ty - fy;
    const dist = Math.hypot(dx, dy) || 1;
    return { x: fx + (dx / dist) * r, y: fy + (dy / dist) * r };
}

const OUTCOME_BADGE: Record<DealSummary['outcome'], string> = {
    won: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300',
    lost: 'bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-300',
    open: 'bg-blue-100 text-blue-700 dark:bg-blue-950/40 dark:text-blue-300',
};

export default function RelationEdge({ id, source, target, data, markerEnd }: EdgeProps<RelationEdgeType>) {
    const selector = useCallback(
        (s: ReactFlowState): Geo | null => {
            const sn = s.nodeLookup.get(source);
            const tn = s.nodeLookup.get(target);
            if (!sn || !tn) return null;
            const sw = sn.measured?.width ?? 0;
            const sh = sn.measured?.height ?? 0;
            const tw = tn.measured?.width ?? 0;
            const th = tn.measured?.height ?? 0;
            return {
                sx: sn.internals.positionAbsolute.x + sw / 2,
                sy: sn.internals.positionAbsolute.y + sh / 2,
                sr: Math.min(sw, sh) / 2,
                tx: tn.internals.positionAbsolute.x + tw / 2,
                ty: tn.internals.positionAbsolute.y + th / 2,
                tr: Math.min(tw, th) / 2,
            };
        },
        [source, target],
    );
    const geo = useStore(selector, geoEqual);
    const [hovered, setHovered] = useState(false);

    if (!geo) return null;

    const sp = boundaryPoint(geo.sx, geo.sy, geo.sr, geo.tx, geo.ty);
    const tp = boundaryPoint(geo.tx, geo.ty, geo.tr, geo.sx, geo.sy);
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

            {deals.length > 0 && (
                <path
                    d={path}
                    fill="none"
                    stroke="transparent"
                    strokeWidth={16}
                    style={{ pointerEvents: 'stroke', cursor: 'pointer' }}
                    onMouseEnter={() => setHovered(true)}
                    onMouseLeave={() => setHovered(false)}
                />
            )}

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