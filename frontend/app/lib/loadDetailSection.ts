import { redirect } from 'next/navigation';

import { ApiError } from '@/app/lib/api';
import { isSessionExpired } from '@/app/lib/sessionExpiry';
import type { DetailSectionResult } from '@/app/lib/detailSectionResult';

/** Loads a detail section without inventing absence; expired sessions follow the existing sign-in flow. */
export async function loadDetailSection<T>(load: () => Promise<T>): Promise<DetailSectionResult<T>> {
    try {
        return { kind: 'loaded', data: await load() };
    } catch (error) {
        if (isSessionExpired(error)) redirect('/auth/login');
        return error instanceof ApiError
            ? { kind: 'failed', status: error.status, reference: error.correlationId }
            : { kind: 'failed' };
    }
}
