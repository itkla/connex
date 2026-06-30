import { toastError, toastSuccess, toastWarn } from '@/app/lib/toast';
import { type BulkOperationResult } from '@/app/lib/types';

/**
 * Localized copy for the three outcomes of a bulk operation. Each builder receives the relevant
 * counts so the message can say e.g. "Tagged 12 contacts" or "Tagged 10 of 12 · 2 failed".
 */
export type BulkToastMessages = {
    success: (count: number) => string;
    partial: (succeeded: number, total: number) => string;
    failure: (failed: number) => string;
};

/**
 * Surfaces a {@link BulkOperationResult} as a single toast — success when every record applied,
 * a warning when some failed, an error when none did — so partial failures are reported without a
 * blocking modal.
 * @returns whether at least one record succeeded (callers refresh/clear selection on a truthy result)
 */
export function notifyBulkResult(result: BulkOperationResult, messages: BulkToastMessages): boolean {
    const total = result.succeeded + result.failed;
    if (result.failed === 0) {
        toastSuccess(messages.success(result.succeeded));
    } else if (result.succeeded === 0) {
        toastError(messages.failure(result.failed));
    } else {
        toastWarn(messages.partial(result.succeeded, total));
    }
    return result.succeeded > 0;
}
