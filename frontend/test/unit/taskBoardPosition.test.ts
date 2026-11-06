import { describe, expect, it } from 'vitest';
import { taskBoardAfterMove, taskBoardIndex, taskBoardPosition } from '@/app/components/activity/tasks/taskBoardPosition';
import type { Task, TaskStatus } from '@/app/lib/types';

function task(id: number, position: number, status: TaskStatus = 'todo'): Task {
    return { id, position, status, description: 'Task', completed: status === 'done', assignedToId: 1, createdAt: '', updatedAt: '' };
}

describe('paged task board drop positions', () => {
    it('places a cross-column drop next to persisted neighbors rather than at the page-local index', () => {
        const tasks = [task(1, 0, 'in_progress'), task(2, 30), task(3, 40)];
        expect(taskBoardPosition(tasks, 1, 'todo', 1)).toBe(40);
        expect(taskBoardPosition(tasks, 1, 'todo', 2)).toBe(41);
    });
    it('accounts for the source leaving its existing column when moving downward', () => {
        const tasks = [task(1, 30), task(2, 40), task(3, 50)];
        expect(taskBoardPosition(tasks, 1, 'todo', 1)).toBe(49);
        expect(taskBoardPosition(tasks, 3, 'todo', 0)).toBe(30);
    });
    it('lets the server append when the current page contains no destination neighbors', () => {
        expect(taskBoardPosition([task(1, 30)], 1, 'in_progress', 0)).toBe(2_147_483_647);
    });
    it('translates persisted results back to visible positions for paged and same-column moves', () => {
        const paged = [task(1, 4, 'in_progress'), task(2, 30), task(3, 40)];
        expect(taskBoardIndex(paged, 1, 'todo', 30)).toBe(0);
        expect(taskBoardIndex(paged, 1, 'todo', 40)).toBe(1);
        expect(taskBoardIndex(paged, 1, 'todo', 41)).toBe(2);
        const contiguous = [task(1, 0), task(2, 1), task(3, 2)];
        expect(taskBoardIndex(contiguous, 1, 'todo', 1)).toBe(1);
        expect(taskBoardIndex(contiguous, 3, 'todo', 0)).toBe(0);
        expect(taskBoardIndex(contiguous, 99, 'todo', 0)).toBe(-1);
    });
    it('uses confirmed peer shifts for successive moves before a paged refresh returns', () => {
        const original = [task(1, 30), task(2, 40), task(3, 50)];
        const moved = taskBoardAfterMove(original, task(1, 50));
        expect(moved.map(({ id, position }) => [id, position])).toEqual([[1, 50], [2, 39], [3, 49]]);
        expect(taskBoardPosition(moved, 3, 'todo', 2)).toBe(50);
        expect(taskBoardIndex(moved, 3, 'todo', 50)).toBe(2);
        expect(original.map(({ position }) => position)).toEqual([30, 40, 50]);
    });
});
