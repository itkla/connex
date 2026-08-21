/** Reports whether a requested lifecycle-dialog transition is safe while a mutation is running. */
export function canChangeCaptureLifecycleDialogOpen(
    busy: boolean,
    nextOpen: boolean,
): boolean {
    return nextOpen || !busy;
}
