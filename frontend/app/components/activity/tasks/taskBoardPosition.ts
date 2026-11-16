import type { Task, TaskStatus } from '@/app/lib/types';

/**
 * Resolves a page-local drop relative to visible neighbors' persisted column positions.
 * An empty visible destination requests the tail; the server clamps the position to its column size.
 */
export function taskBoardPosition(tasks: Task[], taskId: number, status: TaskStatus, index: number): number {
    const source = tasks.find((task) => task.id === taskId);
    const neighbors = tasks.filter((task) => task.id !== taskId && task.status === status)
        .sort((left, right) => left.position - right.position || left.id - right.id);
    const next = neighbors[index];
    const previous = neighbors[index - 1];
    const position = next?.position ?? (previous ? previous.position + 1 : 2_147_483_647);
    return Math.max(0, position - (source?.status === status && source.position < position ? 1 : 0));
}

/** Maps a saved column position back to its visible index after removing the source task. */
export function taskBoardIndex(tasks: Task[], taskId: number, status: TaskStatus, position: number): number {
    const source = tasks.find((task) => task.id === taskId);
    if (!source) return -1;
    return tasks.filter((task) => task.id !== taskId && task.status === status
        && task.position - (source?.status === status && source.position < task.position ? 1 : 0) < position).length;
}

/** Retains acknowledged peer shifts while the paged list refresh is still in flight. */
export function taskBoardAfterMove(tasks: Task[], saved: Task): Task[] {
    const source = tasks.find((task) => task.id === saved.id);
    if (!source) return tasks;
    return tasks.map((task) => {
        if (task.id === saved.id) return saved;
        let position = task.position;
        if (task.status === source.status && position > source.position) position -= 1;
        if (task.status === saved.status && position >= saved.position) position += 1;
        return position === task.position ? task : { ...task, position };
    });
}
