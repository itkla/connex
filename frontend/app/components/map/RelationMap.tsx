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
    type NodeChange,
    type EdgeChange,
} from '@xyflow/react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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

const LABEL_FADE_MIN = 0.1; // at/below this zoom: labels fully faded
const LABEL_FADE_MAX = 0.22; // at/above this zoom: labels fully visible

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

function Flow({ graph, focusId }: { graph: Graph; focusId?: string }) {
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

    const companyCount = useMemo(
        () => graph.nodes.reduce((n, x) => (x.type === 'company' ? n + 1 : n), 0),
        [graph.nodes],
    );
    const lod = useMemo(() => lodConfigForCompanyCount(companyCount), [companyCount]);
    const adjacency = useMemo(() => buildAdjacency(graph.edges), [graph.edges]);

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
            if (activeRef.current?.has(node.id)) return; // already inside this branch
            const ids = activeTreeFor(node.id, adjacency.children, adjacency.parents);
            activeRef.current = ids;
            applyHover(ids);
        },
        [adjacency, applyHover],
    );

    const onNodeMouseLeave = useCallback(() => {
        if (clearTimer.current) clearTimeout(clearTimer.current);
        clearTimer.current = setTimeout(() => {
            activeRef.current = null;
            applyHover(null);
            clearTimer.current = null;
        }, 60);
    }, [applyHover]);

    useEffect(() => () => {
        if (clearTimer.current) clearTimeout(clearTimer.current);
    }, []);

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
                onNodeMouseEnter={onNodeMouseEnter}
                onNodeMouseLeave={onNodeMouseLeave}
                colorMode={resolvedTheme === 'dark' ? 'dark' : 'light'}
                className={cn(settling && 'settle-animate', hovering && 'map-dim')}
                nodeOrigin={NODE_ORIGIN}
                onlyRenderVisibleElements
                minZoom={0.1}
                fitView
            >
                <LabelFade />
                <Background />
                <Controls position="bottom-right" />
                <Panel position="top-right">
                    <Legend />
                </Panel>
            </ReactFlow>
        </LodContext.Provider>
    );
}

export default function RelationMap({ graph, focusId }: { graph: Graph; focusId?: string }) {
    return (
        <div className="w-full h-full">
            <ReactFlowProvider>
                <Flow graph={graph} focusId={focusId} />
            </ReactFlowProvider>
        </div>
    );
}