export type RecordMutationEntity = 'contact' | 'company';

const RECORD_MUTATION_EVENT = 'connex:record-mutated';

type RecordMutationDetail = {
    entity: RecordMutationEntity;
};

declare global {
    interface WindowEventMap {
        'connex:record-mutated': CustomEvent<RecordMutationDetail>;
    }
}

/** Notifies client-owned record browsers after a mutation outside their component tree. */
export function publishRecordMutation(entity: RecordMutationEntity): void {
    window.dispatchEvent(new CustomEvent(RECORD_MUTATION_EVENT, { detail: { entity } }));
}

/** Subscribes to same-window record invalidation events. */
export function subscribeToRecordMutations(
    handler: (entity: RecordMutationEntity) => void,
): () => void {
    const listener = (event: CustomEvent<RecordMutationDetail>) => handler(event.detail.entity);
    window.addEventListener(RECORD_MUTATION_EVENT, listener);
    return () => window.removeEventListener(RECORD_MUTATION_EVENT, listener);
}
