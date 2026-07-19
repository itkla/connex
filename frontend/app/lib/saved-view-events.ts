import type { SavedViewRecordType } from "@/app/lib/types";

const SAVED_VIEW_MUTATION_EVENT = 'connex:saved-view-mutated';

type SavedViewMutationDetail = {
    recordType: SavedViewRecordType;
};

declare global {
    interface WindowEventMap {
        'connex:saved-view-mutated': CustomEvent<SavedViewMutationDetail>;
    }
}

/**
 * Notifies same-window listeners (the pinned-views provider, the sidebar) that a saved view's pin,
 * default, or visibility state changed, so they can refresh without a full navigation.
 */
export function publishSavedViewMutation(recordType: SavedViewRecordType): void {
    if (typeof window === "undefined") return;
    window.dispatchEvent(new CustomEvent(SAVED_VIEW_MUTATION_EVENT, { detail: { recordType } }));
}

/** Subscribes to same-window saved-view mutation events. */
export function subscribeToSavedViewMutations(
    handler: (recordType: SavedViewRecordType) => void,
): () => void {
    const listener = (event: CustomEvent<SavedViewMutationDetail>) => handler(event.detail.recordType);
    window.addEventListener(SAVED_VIEW_MUTATION_EVENT, listener);
    return () => window.removeEventListener(SAVED_VIEW_MUTATION_EVENT, listener);
}
