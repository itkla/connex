import { describe, expect, it } from 'vitest';
import { taskBoardPosition } from '@/app/components/activity/tasks/taskBoardPosition';
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
});
