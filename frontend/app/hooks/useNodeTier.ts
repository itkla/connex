'use client';

import { createContext, useContext } from 'react';
import { useStore, type ReactFlowState } from '@xyflow/react';

export type LodConfig = { dotMax: number };

export const LOD_SMALL_GRAPH = 150;
export const LOD_LARGE_GRAPH = 300;

export function lodConfigForNodeCount(count: number): LodConfig {
    if (count < LOD_SMALL_GRAPH) return { dotMax: 0 };
    if (count > LOD_LARGE_GRAPH) return { dotMax: 0.6 };
    return { dotMax: 0.45 };
}

export const LodContext = createContext<LodConfig>({ dotMax: 0 });

export function useIsDotTier(): boolean {
    const { dotMax } = useContext(LodContext);
    return useStore((s: ReactFlowState) => s.transform[2] < dotMax);
}