'use client';

import {
    ReactFlow,
    ReactFlowProvider,
    applyNodeChanges,
    applyEdgeChanges,
    Background,
    Controls,
    Panel,
    useStoreApi,
    useReactFlow,
    type NodeChange,
    type EdgeChange,
} from '@xyflow/react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useReducedMotion } from 'motion/react';
import { useTheme } from 'next-themes';

import '@xyflow/react/dist/style.css';

import UCNode from '@/app/components/map/UCNode';
import UserNode from '@/app/components/map/UserNode';
import CompanyNode from '@/app/components/map/CompanyNode';
import ContactNode from '@/app/components/map/ContactNode';
import Legend from '@/app/components/map/Legend';
import RelationEdge from '@/app/components/map/edges/RelationEdge';
import { useForceLayout } from '@/app/hooks/useForceLayout';
import { radialLayout } from '@/app/components/map/graph/radialLayout';
import { LodContext, lodConfigForCompanyCount } from '@/app/hooks/useNodeTier';
import { UC_ID } from '@/app/components/map/graph/buildGraph';
import { cn } from '@/lib/utils';
import type { AppNode, Graph, RelationEdge as RelationEdgeType } from './graph/types';
import type { ComputedFrame } from './graph/replay';
import type { TemperatureBand } from '@/app/lib/types';

type ReplayState = { frames: ComputedFrame[]; frameIndex: number };
type FlowProps = { graph: Graph; focusId?: string; replay?: ReplayState; extraEdges?: RelationEdgeType[]; highlightId?: string | null };

const nodeTypes = { uc: UCNode, user: UserNode, company: CompanyNode, contact: ContactNode };
const edgeTypes = { relation: RelationEdge };

const NODE_ORIGIN: [number, number] = [0.5, 0.5];

function buildAdjacency(edges: RelationEdgeType[]) {
    const children = new Map<string, string[]>();
    const parents = new Map<string, string[]>();
    for (const e of edges) {
        (children.get(e.source) ?? children.set(e.source, []).get(e.source)!).push(e.target);
        (parents.get(e.target) ?? parents.set(e.target, []).get(e.target)!).push(e.source);
    }
    return { children, parents };
}

function collectDown(rootId: string, children: Map<string, string[]>, into: Set<string>) {
    const stack = [rootId];
    while (stack.length) {
        const id = stack.pop()!;
        if (into.has(id)) continue;
        into.add(id);
        const kids = children.get(id);
        if (kids) for (const k of kids) stack.push(k);
    }
}

function activeTreeFor(
    nodeId: string,
    children: Map<string, string[]>,
    parents: Map<string, string[]>,
): Set<string> {
    const ids = new Set<string>([UC_ID]);

    collectDown(nodeId, children, ids);

    const stack = [...(parents.get(nodeId) ?? [])];
    while (stack.length) {
        const id = stack.pop()!;
        if (ids.has(id)) continue;
        ids.add(id);
        const ps = parents.get(id);
        if (ps) for (const p of ps) stack.push(p);
    }

    return ids;
}

const LABEL_FADE_MIN = 0.1;
const LABEL_FADE_MAX = 0.22;

function LabelFade() {
    const store = useStoreApi();
    useEffect(() => {
        const apply = () => {
            const { transform, domNode } = store.getState();
            if (!domNode) return;
            const z = transform[2];
            const o = Math.max(0, Math.min(1, (z - LABEL_FADE_MIN) / (LABEL_FADE_MAX - LABEL_FADE_MIN)));
            domNode.style.setProperty('--label-opacity', String(o));
        };
        apply();
        return store.subscribe(apply);
    }, [store]);
    return null;
}

function Flow({ graph, focusId, replay, extraEdges, highlightId }: FlowProps) {
    const [nodes, setNodes] = useState<AppNode[]>(() => {
        const seeded = radialLayout(graph);
        if (!focusId) return seeded;
        return seeded.map((n): AppNode => {
            if (n.id !== focusId) return n;
            if (n.type === 'company') return { ...n, data: { ...n.data, expanded: true } };
            if (n.type === 'contact') return { ...n, data: { ...n.data, expanded: true } };
            return n;
        });
    });
    const [edges, setEdges] = useState<RelationEdgeType[]>(graph.edges);
    const [hovering, setHovering] = useState(false);

    const { resolvedTheme } = useTheme();
    const reduceMotion = useReducedMotion() ?? false;
    const { fitView } = useReactFlow();

    const companyCount = useMemo(
        () => graph.nodes.reduce((n, x) => (x.type === 'company' ? n + 1 : n), 0),
        [graph.nodes],
    );
    const lod = useMemo(() => lodConfigForCompanyCount(companyCount), [companyCount]);
    const adjacency = useMemo(() => buildAdjacency(graph.edges), [graph.edges]);

    const liveWarmth = useMemo(
        () =>
            new Map<string, TemperatureBand | undefined>(
                graph.nodes.map((n) => [n.id, n.type === 'company' || n.type === 'contact' ? n.data.warmth : undefined]),
            ),
        [graph.nodes],
    );
    const liveEdgeColor = useMemo(
        () => new Map<string, string | undefined>(graph.edges.map((e) => [e.id, e.data?.ccColor])),
        [graph.edges],
    );

    const { settling, onNodeDragStart, onNodeDragStop } = useForceLayout(focusId);

    const applyHover = useCallback((active: Set<string> | null) => {
        setNodes((snapshot) => {
            let changed = false;
            const next = snapshot.map((n): AppNode => {
                const on = active?.has(n.id) ?? false;
                const className = on ? 'rf-active' : undefined;
                if (n.type === 'company') {
                    if (!!n.data.hovered === on && n.className === className) return n;
                    changed = true;
                    return { ...n, className, data: { ...n.data, hovered: on } };
                }
                if (n.type === 'contact') {
                    if (!!n.data.hovered === on && n.className === className) return n;
                    changed = true;
                    return { ...n, className, data: { ...n.data, hovered: on } };
                }
                if (n.className === className) return n;
                changed = true;
                return { ...n, className };
            });
            return changed ? next : snapshot;
        });
        setEdges((snapshot) => {
            let changed = false;
            const next = snapshot.map((e) => {
                const on = active ? active.has(e.source) && active.has(e.target) : false;
                const className = on ? 'rf-active' : undefined;
                if (e.className === className) return e;
                changed = true;
                return { ...e, className };
            });
            return changed ? next : snapshot;
        });
        setHovering(active != null);
    }, []);

    const activeRef = useRef<Set<string> | null>(null);
    const pinnedRef = useRef<Set<string> | null>(null);
    const clearTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

    const onNodeMouseEnter = useCallback(
        (_: unknown, node: AppNode) => {
            if (clearTimer.current) {
                clearTimeout(clearTimer.current);
                clearTimer.current = null;
            }
            if (node.id === UC_ID) {
                if (activeRef.current) {
                    activeRef.current = null;
                    applyHover(null);
                }
                return;
            }
            if (activeRef.current?.has(node.id)) return;
            const ids = activeTreeFor(node.id, adjacency.children, adjacency.parents);
            activeRef.current = ids;
            applyHover(ids);
        },
        [adjacency, applyHover],
    );

    const onNodeMouseLeave = useCallback(() => {
        if (clearTimer.current) clearTimeout(clearTimer.current);
        clearTimer.current = setTimeout(() => {
            activeRef.current = pinnedRef.current;
            applyHover(pinnedRef.current);
            clearTimer.current = null;
        }, 60);
    }, [applyHover]);

    useEffect(() => () => {
        if (clearTimer.current) clearTimeout(clearTimer.current);
    }, []);

    useEffect(() => {
        const raf = requestAnimationFrame(() => {
            if (!highlightId) {
                pinnedRef.current = null;
                activeRef.current = null;
                applyHover(null);
                return;
            }
            const ids = activeTreeFor(highlightId, adjacency.children, adjacency.parents);
            pinnedRef.current = ids;
            activeRef.current = ids;
            applyHover(ids);
            fitView({ nodes: [{ id: highlightId }], duration: reduceMotion ? 0 : 600, maxZoom: 1.1, padding: 0.6 });
        });
        return () => cancelAnimationFrame(raf);
    }, [highlightId, adjacency, applyHover, fitView, reduceMotion]);

    const onNodesChange = useCallback(
        (changes: NodeChange<AppNode>[]) =>
            setNodes((snapshot) => applyNodeChanges(changes, snapshot)),
        [],
    );
    const onEdgesChange = useCallback(
        (changes: EdgeChange<RelationEdgeType>[]) =>
            setEdges((snapshot) => applyEdgeChanges(changes, snapshot)),
        [],
    );

    const applyFrame = useCallback((frame: ComputedFrame) => {
        setNodes((snapshot) => {
            let changed = false;
            const next = snapshot.map((n): AppNode => {
                const present = frame.presentNodeIds.has(n.id);
                const opacity = present ? 1 : 0;
                const pointerEvents: 'none' | undefined = present ? undefined : 'none';
                const style = { ...n.style, opacity, pointerEvents };
                const styleChanged = ((n.style?.opacity as number | undefined) ?? 1) !== opacity;
                const band = frame.nodeWarmth.get(n.id);
                if (n.type === 'company') {
                    const warmthChanged = band !== undefined && n.data.warmth !== band;
                    if (!styleChanged && !warmthChanged) return n;
                    changed = true;
                    return { ...n, style, data: band !== undefined ? { ...n.data, warmth: band } : n.data };
                }
                if (n.type === 'contact') {
                    const warmthChanged = band !== undefined && n.data.warmth !== band;
                    if (!styleChanged && !warmthChanged) return n;
                    changed = true;
                    return { ...n, style, data: band !== undefined ? { ...n.data, warmth: band } : n.data };
                }
                if (!styleChanged) return n;
                changed = true;
                return { ...n, style };
            });
            return changed ? next : snapshot;
        });
        setEdges((snapshot) => {
            let changed = false;
            const next = snapshot.map((e) => {
                const hidden = !frame.presentEdgeIds.has(e.id);
                const cc = frame.edgeCcColor.get(e.id);
                const ccChanged = cc !== undefined && e.data?.ccColor !== cc;
                if ((e.hidden ?? false) === hidden && !ccChanged) return e;
                changed = true;
                return { ...e, hidden, data: cc !== undefined && e.data ? { ...e.data, ccColor: cc } : e.data };
            });
            return changed ? next : snapshot;
        });
    }, []);

    const mergeExtra = useCallback((extra: RelationEdgeType[]) => {
        setEdges((snapshot) => {
            const have = new Set(snapshot.map((e) => e.id));
            const additions = extra.flatMap((edge) => have.has(edge.id) ? [] : [{ ...edge, hidden: true }]);
            return additions.length > 0 ? [...snapshot, ...additions] : snapshot;
        });
    }, []);

    const resetToLive = useCallback(() => {
        setNodes((snapshot) => {
            let changed = false;
            const next = snapshot.map((n): AppNode => {
                const styleChanged = ((n.style?.opacity as number | undefined) ?? 1) !== 1 || n.style?.pointerEvents != null;
                const style = { ...n.style, opacity: 1, pointerEvents: undefined as 'none' | undefined };
                const live = liveWarmth.get(n.id);
                if (n.type === 'company') {
                    if (!styleChanged && n.data.warmth === live) return n;
                    changed = true;
                    return { ...n, style, data: { ...n.data, warmth: live } };
                }
                if (n.type === 'contact') {
                    if (!styleChanged && n.data.warmth === live) return n;
                    changed = true;
                    return { ...n, style, data: { ...n.data, warmth: live } };
                }
                if (!styleChanged) return n;
                changed = true;
                return { ...n, style };
            });
            return changed ? next : snapshot;
        });
        setEdges((snapshot) => {
            let changed = false;
            const next = snapshot.map((e) => {
                const hidden = !liveEdgeColor.has(e.id);
                const cc = liveEdgeColor.get(e.id);
                const ccChanged = cc !== undefined && e.data?.ccColor !== cc;
                if ((e.hidden ?? false) === hidden && !ccChanged) return e;
                changed = true;
                return { ...e, hidden, data: cc !== undefined && e.data ? { ...e.data, ccColor: cc } : e.data };
            });
            return changed ? next : snapshot;
        });
    }, [liveWarmth, liveEdgeColor]);

    const frame = replay ? replay.frames[replay.frameIndex] : undefined;
    useEffect(() => {
        const raf = requestAnimationFrame(() => {
            if (extraEdges && extraEdges.length > 0) mergeExtra(extraEdges);
            if (frame) applyFrame(frame);
            else resetToLive();
        });
        return () => cancelAnimationFrame(raf);
    }, [frame, extraEdges, applyFrame, mergeExtra, resetToLive]);

    return (
        <LodContext.Provider value={lod}>
            <ReactFlow
                nodes={nodes}
                edges={edges}
                nodeTypes={nodeTypes}
                edgeTypes={edgeTypes}
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                onNodeDragStart={onNodeDragStart}
                onNodeDragStop={onNodeDragStop}
                onNodeMouseEnter={replay ? undefined : onNodeMouseEnter}
                onNodeMouseLeave={replay ? undefined : onNodeMouseLeave}
                colorMode={resolvedTheme === 'dark' ? 'dark' : 'light'}
                className={cn(settling && !reduceMotion && 'settle-animate', !replay && hovering && 'map-dim')}
                nodeOrigin={NODE_ORIGIN}
                onlyRenderVisibleElements
                minZoom={0.1}
                fitView
                fitViewOptions={{ duration: reduceMotion ? 0 : undefined }}
            >
                <LabelFade />
                <Background />
                <Controls position="bottom-right" />
                <Panel position="top-left">
                    <Legend />
                </Panel>
            </ReactFlow>
        </LodContext.Provider>
    );
}

export default function RelationMap({ graph, focusId, replay, extraEdges, highlightId }: FlowProps) {
    return (
        <div className="w-full h-full">
            <ReactFlowProvider>
                <Flow graph={graph} focusId={focusId} replay={replay} extraEdges={extraEdges} highlightId={highlightId} />
            </ReactFlowProvider>
        </div>
    );
}
