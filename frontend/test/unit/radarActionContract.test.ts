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
        const board = source('app/components/radar/RadarBoard.tsx');
        const overlay = source('app/components/actions/ActionOverlayHost.tsx');
        const dialog = source('app/components/activity/tasks/TaskDialog.tsx');
        const actions = source('app/lib/actions/seedActions.ts');

        expect(overlay).toContain('createRadarTask(');
        expect(overlay).toContain('submitRadarTaskWithCurrentSignal(');
        expect(overlay).toContain("compact={radarTask?.mode === 'warm_path'}");
        expect(overlay).toContain('hideLinks={radarTask !== undefined}');
        expect(overlay).toContain('draftPersistence={radarTask === undefined}');
        expect(overlay).toContain('preserveDraftOnClose={radarTask !== undefined}');
        expect(overlay).toContain('submissionBlockedMessage={taskSubmissionBlockedMessage}');
        expect(overlay).toContain('radarTask.signalState.refresh(undefined, "checking")');
        expect(overlay).toContain('radarTask.onRefresh();');
        expect(dialog).toContain('createRequest = createTask');
        expect(dialog).toContain('Boolean(submissionBlockedMessage)');
        expect(dialog).toContain('!compact ?');
        expect(board).toContain('activeRadarTask.signalState.refresh(');
        expect(board).toContain('radarTaskDraftsRef.current.set(signal.id, nextDraft)');
        expect(actions).toContain('draft: helpers.radarTask?.draft');
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
        expect(board).toContain('nextRefreshDelay !== 0');
        expect(board).toContain("t('task.warmPathDescription'");
        expect(card).not.toContain("value.includes('_')");
        expect(card).not.toContain("if (key === 'subject') return signal.subject.label");
    });

    it('invalidates the mounted refresh session before teardown can be followed by more work', () => {
        const board = source('app/components/radar/RadarBoard.tsx');

        expect(board).toContain('if (!session.active || refreshSessionRef.current !== session) return;');
        expect(board).toContain('if (!session.active || nextRefreshDelay !== 0)');
        expect(board).toContain('session.active = false;');
        expect(board).toContain('refreshSessionRef.current = null;');
    });

    it('releases the Radar task subscription on close and its retained request after exit', () => {
        const overlay = source('app/components/actions/ActionOverlayHost.tsx');
        const dialog = source('app/components/activity/tasks/TaskDialog.tsx');
        const responsiveDialog = source('components/ui/responsive-dialog.tsx');

        expect(overlay).toContain('const subscribedRadarTask = visible ? radarTask : undefined;');
        expect(overlay).toContain('subscribedRadarTask?.signalState.subscribe');
        expect(overlay).toContain('current?.generation === generation ? null : current');
        expect(overlay).toContain('onCloseComplete={() => handleRenderedCloseComplete(rendered.generation)}');
        expect(dialog).toContain('onCloseComplete={onCloseComplete}');
        expect(responsiveDialog).toContain('onOpenChangeComplete={handleOpenChangeComplete}');
        expect(responsiveDialog).toContain('onCloseAutoFocus={onCloseComplete}');
    });
});
