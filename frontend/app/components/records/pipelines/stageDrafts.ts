type StageFlags = { success?: boolean; failure?: boolean };

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
