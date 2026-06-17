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
import { useCallback, useEffect, useRef, useState } from 'react';
import { UC_ID } from '@/app/components/map/graph/buildGraph';
import { RING_RADIUS } from '@/app/components/map/graph/radialLayout';
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
const MAX_TICKS = 300;
const EPSILON = 0.5;
const SETTLE_MS = 700;

function commitPositions(
    nodes: AppNode[],
    index: Map<string, SimNode>,
    dragging: Set<string>,
): AppNode[] {
    let changed = false;
    const next = nodes.map((node) => {
        if (dragging.has(node.id)) return node;
        const sn = index.get(node.id);
        if (!sn) return node;
        const x = sn.x ?? 0;
        const y = sn.y ?? 0;
        if (Math.abs(node.position.x - x) + Math.abs(node.position.y - y) < EPSILON) return node;
        changed = true;
        return { ...node, position: { x, y } };
    });
    return changed ? next : nodes;
}

export function useForceLayout(focusId?: string) {
    const { getNodes, getEdges, setNodes, fitView, setCenter } =
        useReactFlow<AppNode, RelationEdge>();
    const initialized = useNodesInitialized();

    const [settling, setSettling] = useState(true);

    const draggingRef = useRef<Set<string>>(new Set());
    const simRef = useRef<Simulation<SimNode, SimLink> | null>(null);
    const indexRef = useRef<Map<string, SimNode>>(new Map());
    const runningRef = useRef(false);
    const frameRef = useRef(0);

    const startLiveLoop = useCallback(() => {
        if (runningRef.current) return;
        const sim = simRef.current;
        const index = indexRef.current;
        if (!sim) return;
        runningRef.current = true;

        const tick = () => {
            const dragging = draggingRef.current;
            for (const n of getNodes()) {
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
            setNodes((nodes) => commitPositions(nodes, index, dragging));

            if (sim.alpha() > ALPHA_MIN || dragging.size > 0) {
                frameRef.current = requestAnimationFrame(tick);
            } else {
                runningRef.current = false;
            }
        };

        frameRef.current = requestAnimationFrame(tick);
    }, [getNodes, setNodes]);

    const reheat = useCallback(() => {
        const sim = simRef.current;
        if (!sim) return;
        sim.alpha(REHEAT_ALPHA);
        startLiveLoop();
    }, [startLiveLoop]);

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
            .alphaDecay(0.05)
            .stop();

        const uc = index.get(UC_ID);
        if (uc) {
            uc.fx = 0;
            uc.fy = 0;
        }

        simRef.current = sim;
        indexRef.current = index;

        let focusFrame = 0;
        let settleTimer: ReturnType<typeof setTimeout> | undefined;

        const settleFrame = requestAnimationFrame(() => {
            for (let i = 0; i < MAX_TICKS && sim.alpha() > ALPHA_MIN; i++) sim.tick();
            setNodes((nodes) => commitPositions(nodes, index, draggingRef.current));

            focusFrame = requestAnimationFrame(() => {
                const sn = focusId ? index.get(focusId) : undefined;
                if (sn) {
                    setCenter(sn.x ?? 0, sn.y ?? 0, { zoom: 1, duration: SETTLE_MS });
                } else {
                    fitView({ padding: 0.2, duration: SETTLE_MS });
                }
            });

            settleTimer = setTimeout(() => setSettling(false), SETTLE_MS + 80);
        });

        return () => {
            cancelAnimationFrame(settleFrame);
            cancelAnimationFrame(focusFrame);
            cancelAnimationFrame(frameRef.current);
            if (settleTimer) clearTimeout(settleTimer);
            runningRef.current = false;
            sim.stop();
            simRef.current = null;
        };
    }, [initialized, getNodes, getEdges, setNodes, fitView, setCenter, focusId]);

    const onNodeDragStart = useCallback(
        (_: unknown, node: AppNode) => {
            setSettling(false);
            draggingRef.current.add(node.id);
            reheat();
        },
        [reheat],
    );

    const onNodeDragStop = useCallback(
        (_: unknown, node: AppNode) => {
            draggingRef.current.delete(node.id);
            reheat();
        },
        [reheat],
    );

    return { initialized, settling, onNodeDragStart, onNodeDragStop };
}