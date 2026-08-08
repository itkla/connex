import type { AiGenerationStatus } from '@/app/lib/types';

type ResolveOptions = {
    signal?: AbortSignal | null;
    shouldRetryError?: (error: unknown) => boolean;
    now?: () => number;
    sleep?: (milliseconds: number, signal?: AbortSignal | null) => Promise<void>;
};

/** Terminal failure returned by the asynchronous AI generation contract. */
export class AiGenerationError extends Error {
    constructor(
        readonly status: 'failed' | 'timed_out',
        readonly reason: string,
    ) {
        super(reason);
        this.name = 'AiGenerationError';
    }
}

function abortableSleep(milliseconds: number, signal?: AbortSignal | null): Promise<void> {
    return new Promise((resolve, reject) => {
        if (signal?.aborted) {
            reject(signal.reason);
            return;
        }
        const aborted = () => {
            clearTimeout(timer);
            reject(signal?.reason);
        };
        const timer = setTimeout(() => {
            signal?.removeEventListener('abort', aborted);
            resolve();
        }, milliseconds);
        signal?.addEventListener('abort', aborted, { once: true });
    });
}

/**
 * Polls one bounded server handle until it resolves or reports an explicit terminal state.
 * Transient transport failures may be retried only inside the server-declared poll window.
 */
export async function resolveAiGeneration<T>(
    initial: AiGenerationStatus<T>,
    poll: (handle: string) => Promise<AiGenerationStatus<T>>,
    options: ResolveOptions = {},
): Promise<T> {
    const now = options.now ?? Date.now;
    const sleep = options.sleep ?? abortableSleep;
    const deadline = Date.parse(initial.expiresAt);
    let current = initial;

    while (true) {
        options.signal?.throwIfAborted();
        if (current.status === 'resolved') return current.result;
        if (current.status === 'failed' || current.status === 'timed_out') {
            throw new AiGenerationError(current.status, current.reason);
        }
        if (!Number.isFinite(deadline) || now() >= deadline) {
            throw new AiGenerationError('timed_out', 'poll_window_expired');
        }

        const delay = Math.min(Math.max(current.retryAfterMs, 250), deadline - now());
        await sleep(delay, options.signal);
        options.signal?.throwIfAborted();
        try {
            current = await poll(current.handle);
        } catch (error) {
            if (options.signal?.aborted || !options.shouldRetryError?.(error)) throw error;
        }
    }
}
