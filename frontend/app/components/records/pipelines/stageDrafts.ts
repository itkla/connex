type StageFlags = { success?: boolean; failure?: boolean };

/** Creates a stable editor identity that never enters the stage write payload. */
export function newStageDraft() {
    return { clientId: crypto.randomUUID(), name: '', success: false, failure: false };
}

/** Identifies a persisted or unsaved stage independently of its array position. */
export function stageDraftKey(stage: { id?: number | null; clientId?: string }): string {
    if (stage.id != null) return `saved-${stage.id}`;
    if (stage.clientId) return stage.clientId;
    throw new Error('An unsaved stage requires a client identity');
}

/** Restores the last order while preserving field edits and excluding removed stages. */
export function restoreStageOrder<T extends { id?: number | null; clientId?: string }>(stages: T[], order?: string[]): T[] {
    if (!order) return stages;
    const positions = new Map(order.map((key, index) => [key, index]));
    return [...stages].sort((a, b) => (positions.get(stageDraftKey(a)) ?? order.length) - (positions.get(stageDraftKey(b)) ?? order.length));
}

/** Inserts a new open stage before the first terminal stage without changing saved order. */
export function insertOpenStage<T extends StageFlags>(stages: T[], stage: T): T[] {
    const terminalIndex = stages.findIndex((item) => item.success || item.failure);
    const index = terminalIndex < 0 ? stages.length : terminalIndex;
    return [...stages.slice(0, index), stage, ...stages.slice(index)];
}

/** Moves one stage while retaining its identity, name, and outcome flags. */
export function moveStage<T>(stages: T[], from: number, to: number): T[] {
    if (from < 0 || from >= stages.length || to < 0 || to >= stages.length) return stages;
    const next = [...stages];
    const [stage] = next.splice(from, 1);
    next.splice(to, 0, stage);
    return next;
}
