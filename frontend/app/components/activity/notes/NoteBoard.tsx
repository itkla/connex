'use client';

import { Button } from "@/components/ui/button";
import { PlusIcon } from "@heroicons/react/24/solid";
import { useTranslations } from "next-intl";
import { Note } from "@/app/lib/types";
import { Background, Panel, Controls, ReactFlow, type Edge, MiniMap, applyNodeChanges, NodeChange, applyEdgeChanges, EdgeChange } from "@xyflow/react";
import { Legend } from "recharts";
import { useCallback } from "react";
import { NoteNode, NoteEdge, EdgeTypes, NodeTypes } from "./types";

// export default function NoteBoard({ allNotes }: { allNotes: Note[] }) {
//     const t = useTranslations('NoteBoard');

//     const nodes = allNotes.map((note) => ({
//         id: note.id.toString(),
//         position: { x: 0, y: 0 },
//         data: note,
//     }));

//     const edges: Edge[] = [];

//     const nodeTypes: Record<string, NodeType<NoteNode>> = { note: NoteNode };
//     const edgeTypes: EdgeTypes = { note: NoteEdge };

//     const onNodesChange = useCallback((changes: NodeChange<NoteNode>[]) => {
//         setNodes((snapshot) => applyNodeChanges(changes, snapshot));
//     }, []);

//     const onEdgesChange = useCallback((changes: EdgeChange<NoteEdge>[]) => {
//         setEdges((snapshot) => applyEdgeChanges(changes, snapshot));
//     }, []);

//     const onNodeDragStart = useCallback((event: React.MouseEvent<HTMLDivElement>, node: Node) => {
//         event.stopPropagation();
//     }, []);

//     const onNodeDragStop = useCallback((event: React.MouseEvent<HTMLDivElement>, node: Node) => {
//         event.stopPropagation();
//     }, []);

//     return (
//         <div>
//             <div className="space-y-6">
//                 <div className="flex items-center justify-between">
//                     <h1 className="text-4xl font-extrabold">{t('title')}</h1>
//                     {/* // TODO: make the new note button inside the canvas */}
//                     {/* <Button className="bg-brand text-white" aria-label={t('addCompanyAriaLabel')} onClick={() => setNewDialogOpen(true)}>
//                         <PlusIcon strokeWidth={2.5} />
//                         {t('new')}
//                     </Button> */}
//                 </div>
//                 <ReactFlow 
//                     nodes={nodes} 
//                     edges={edges}
//                     nodeTypes={nodeTypes}
//                     edgeTypes={edgeTypes}
//                     onNodesChange={onNodesChange}
//                     onEdgesChange={onEdgesChange}
//                     onNodeDragStart={onNodeDragStart}
//                     onNodeDragStop={onNodeDragStop}
//                     onlyRenderVisibleElements
//                     minZoom={0.1}
//                     fitView
//                 >
//                     <Background />
//                     <Controls position="bottom-right" />
//                     <Panel position="top-right">
//                         <Legend />
//                     </Panel>
//                     <MiniMap />
//                 </ReactFlow>
//             </div>
//         </div>
//     )
// }