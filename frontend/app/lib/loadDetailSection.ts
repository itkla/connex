import { redirect } from 'next/navigation';
import { headers } from 'next/headers';

import { ApiError } from '@/app/lib/api';
import { signInHref, isSessionExpired } from '@/app/lib/sessionExpiry';
import type { DetailSectionResult } from '@/app/lib/detailSectionResult';

/** Loads a detail section without inventing absence; expired sessions follow the existing sign-in flow. */
export async function loadDetailSection<T>(load: () => Promise<T>): Promise<DetailSectionResult<T>> {
    try {
        return { kind: 'loaded', data: await load() };
    } catch (error) {
        if (isSessionExpired(error)) {
            const path = (await headers()).get('x-pathname') ?? '/dashboard';
            redirect(signInHref(path, ''));
        }
        return error instanceof ApiError
            ? { kind: 'failed', status: error.status, reference: error.correlationId }
            : { kind: 'failed' };
    }
}
