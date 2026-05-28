import { Note } from "@/app/lib/types";

export type NoteNode = {
    id: string;
    position: { x: number; y: number };
    data: Note;
};

export type NoteEdge = {
    id: string;
    source: string;
    target: string;
    type: 'note';
};

export type EdgeTypes = {
    note: NoteEdge;
};

export type NodeTypes = {
    note: NoteNode;
};