'use client';

import { createContext, useContext } from 'react';

export const LOD_MIN_COMPANIES = 10;

export type LodConfig = { dotEnabled: boolean };

export function lodConfigForCompanyCount(companyCount: number): LodConfig {
    return { dotEnabled: companyCount >= LOD_MIN_COMPANIES };
}

export const LodContext = createContext<LodConfig>({ dotEnabled: false });

export function useDotEnabled(): boolean {
    return useContext(LodContext).dotEnabled;
}