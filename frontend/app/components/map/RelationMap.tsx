'use client';

import {
    ReactFlow,
    ReactFlowProvider,
    applyNodeChanges,
    applyEdgeChanges,
    Background,
    Controls,
    Panel,
    type NodeChange,
    type EdgeChange,
} from '@xyflow/react';
import { useCallback, useState } from 'react';

import '@xyflow/react/dist/style.css';

import UCNode from '@/app/components/map/UCNode';
import UserNode from '@/app/components/map/UserNode';
import CompanyNode from '@/app/components/map/CompanyNode';
import ContactNode from '@/app/components/map/ContactNode';
import Legend from '@/app/components/map/Legend';
import RelationEdge from '@/app/components/map/edges/RelationEdge';
import { useForceLayout } from '@/app/hooks/useForceLayout';
import { radialLayout } from '@/app/components/map/graph/radialLayout';
import type { AppNode, Graph, RelationEdge as RelationEdgeType } from './graph/types';

const nodeTypes = { uc: UCNode, user: UserNode, company: CompanyNode, contact: ContactNode };
const edgeTypes = { relation: RelationEdge };

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

    const { onNodeDragStart, onNodeDragStop } = useForceLayout(focusId);

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
        <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onNodeDragStart={onNodeDragStart}
            onNodeDragStop={onNodeDragStop}
            onlyRenderVisibleElements
            minZoom={0.1}
            fitView
        >
            <Background />
            <Controls position="bottom-right" />
            <Panel position="top-right">
                <Legend />
            </Panel>
        </ReactFlow>
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