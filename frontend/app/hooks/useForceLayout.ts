'use client';

// code borrowed from d3 and stackoverflow

import {
    forceSimulation,
    forceLink,
    forceManyBody,
    forceRadial,
    forceCollide,
    type Simulation,
    type SimulationNodeDatum,
    type SimulationLinkDatum,
} from 'd3';
import { useNodesInitialized, useReactFlow } from '@xyflow/react';
import { useCallback, useEffect, useRef } from 'react';
import { UC_ID } from '@/app/components/map/graph/buildGraph';
import { RING_RADIUS } from '@/app/components/map/graph/layout';
import type { AppNode, RelationEdge } from '@/app/components/map/graph/types';

type SimNode = SimulationNodeDatum & { id: string; kind: string };
type SimLink = SimulationLinkDatum<SimNode> & { variant?: 'uc-user' | 'rel-cc' | 'cc-co' };

const RADIAL_STRENGTH: Record<string, number> = {
    uc: 0,
    user: 0.45,
    company: 0.35,
    contact: 0,
};

const LINK_DISTANCE: Record<NonNullable<SimLink['variant']>, number> = {
    'uc-user': 180,
    'rel-cc': 230,
    'cc-co': 80,
};
const LINK_STRENGTH: Record<NonNullable<SimLink['variant']>, number> = {
    'uc-user': 0.2,
    'rel-cc': 0.12,
    'cc-co': 0.6,
};
const ALPHA_MIN = 0.01;
const REHEAT_ALPHA = 0.5;

export function useForceLayout(focusId?: string) {
    const { getNodes, getEdges, setNodes, fitView, getNode, setCenter } = useReactFlow<AppNode, RelationEdge>();
    const initialized = useNodesInitialized();

    const draggingRef = useRef<Set<string>>(new Set());
    const runningRef = useRef(false);
    const reheatRef = useRef<(() => void) | null>(null);
    const fittedRef = useRef(false);

    useEffect(() => {
        if (!initialized) return;
        const rfNodes = getNodes();
        if (rfNodes.length === 0) return;

        const simNodes: SimNode[] = rfNodes.map((n) => ({
            id: n.id,
            kind: n.type ?? 'company',
            x: n.position.x,
            y: n.position.y,
        }));
        const index = new Map(simNodes.map((n) => [n.id, n] as const));
        const simLinks: SimLink[] = getEdges().map((e) => ({
            source: e.source,
            target: e.target,
            variant: e.data?.variant,
        }));

        const sim: Simulation<SimNode, SimLink> = forceSimulation<SimNode>(simNodes)
            .force('charge', forceManyBody<SimNode>().strength((d) => (d.id === UC_ID ? -2200 : -650)))
            .force(
                'link',
                forceLink<SimNode, SimLink>(simLinks)
                    .id((d) => d.id)
                    .distance((l) => LINK_DISTANCE[l.variant ?? 'rel-cc'])
                    .strength((l) => LINK_STRENGTH[l.variant ?? 'rel-cc']),
            )
            .force(
                'radial',
                forceRadial<SimNode>((d) => RING_RADIUS[d.kind] ?? RING_RADIUS.company, 0, 0).strength(
                    (d) => RADIAL_STRENGTH[d.kind] ?? 0,
                ),
            )
            .force('collide', forceCollide<SimNode>(58))
            .alpha(0.6)
            .stop();

        let frame = 0;

        const tick = () => {
            const dragging = draggingRef.current;
            const live = getNodes();
            for (const n of live) {
                const sn = index.get(n.id);
                if (!sn) continue;
                if (n.id === UC_ID) {
                    sn.fx = 0;
                    sn.fy = 0;
                } else if (dragging.has(n.id)) {
                    sn.fx = n.position.x;
                    sn.fy = n.position.y;
                } else {
                    sn.fx = null;
                    sn.fy = null;
                }
            }

            sim.tick();

            setNodes((nodes) =>
                nodes.map((node) => {
                    if (dragging.has(node.id)) return node;
                    const sn = index.get(node.id);
                    return sn ? { ...node, position: { x: sn.x ?? 0, y: sn.y ?? 0 } } : node;
                }),
            );

            if (sim.alpha() > ALPHA_MIN || dragging.size > 0) {
                frame = requestAnimationFrame(tick);
            } else {
                runningRef.current = false;
                if (!fittedRef.current) {
                    fittedRef.current = true;
                    const focusNode = focusId ? getNode(focusId) : undefined;
                    if (focusNode) {
                        const w = focusNode.measured?.width ?? 0;
                        const h = focusNode.measured?.height ?? 0;
                        setCenter(focusNode.position.x + w / 2, focusNode.position.y + h / 2, {
                            zoom: 1,
                            duration: 700,
                        });
                    } else {
                        fitView({ padding: 0.2, duration: 400 });
                    }
                }
            }
        };

        const start = () => {
            if (runningRef.current) return;
            runningRef.current = true;
            frame = requestAnimationFrame(tick);
        };

        reheatRef.current = () => {
            sim.alpha(REHEAT_ALPHA);
            start();
        };

        start();

        return () => {
            cancelAnimationFrame(frame);
            runningRef.current = false;
            reheatRef.current = null;
            sim.stop();
        };
    }, [initialized, getNodes, getEdges, setNodes, fitView, getNode, setCenter, focusId]);

    const onNodeDragStart = useCallback((_: unknown, node: AppNode) => {
        draggingRef.current.add(node.id);
        reheatRef.current?.();
    }, []);

    const onNodeDragStop = useCallback((_: unknown, node: AppNode) => {
        draggingRef.current.delete(node.id);
        reheatRef.current?.();
    }, []);

    return { initialized, onNodeDragStart, onNodeDragStop };
}