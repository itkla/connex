import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
    return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('Radar action integration', () => {
    it('runs task creation and record opening through the shared action registry', () => {
        const board = source('app/components/radar/RadarBoard.tsx');

        expect(board).toContain("run('create.task'");
        expect(board).toContain("run('record.open'");
        expect(board).not.toContain('createRadarTask(');
    });

    it('routes the shared task composer submission through the Radar endpoint', () => {
        const overlay = source('app/components/actions/ActionOverlayHost.tsx');
        const dialog = source('app/components/activity/tasks/TaskDialog.tsx');

        expect(overlay).toContain('createRadarTask(');
        expect(overlay).toContain("compact={radarTask?.mode === 'warm_path'}");
        expect(overlay).toContain('hideLinks={radarTask !== undefined}');
        expect(overlay).toContain('draftPersistence={radarTask === undefined}');
        expect(dialog).toContain('createRequest = createTask');
        expect(dialog).toContain('onPersistDraft={draftPersistence ? draft.persist : undefined}');
        expect(dialog).toContain('!compact ?');
    });

    it('disables every enabled-looking card while a global action gate is occupied', () => {
        const board = source('app/components/radar/RadarBoard.tsx');
        const card = source('app/components/radar/RadarSignalCard.tsx');

        expect(board).toContain('busy={busyId !== null}');
        expect(card).toContain('disabled={busy || followed}');
        expect(card).toContain("usePermissionCheck('PERSON_UPDATE')");
        expect(card).toContain('usePermissionsRefresh()');
        expect(board).toContain('const refreshed = await getRadar()');
        expect(board).toContain(">('checking')");
        expect(card).toContain("freshnessStatus !== 'current'");
        expect(board).toContain('radarEvidenceRefreshDelay(');
        expect(board).toContain('refreshRadarRef.current();');
        expect(board).toContain('if (nextRefreshDelay !== 0)');
        expect(board).toContain("t('task.warmPathDescription'");
        expect(card).not.toContain("value.includes('_')");
        expect(card).not.toContain("if (key === 'subject') return signal.subject.label");
    });
});
