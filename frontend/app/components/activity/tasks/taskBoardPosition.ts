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
