'use client';

import { forwardRef, useCallback, useImperativeHandle, useState } from 'react';

import ConfirmDiscardDialog from '@/app/components/ConfirmDiscardDialog';
import { useUnsavedChangesGuard } from '@/app/hooks/useUnsavedChangesGuard';

export type MobileDealDiscardControls = {
    handleOpenChange: (open: boolean) => void;
    requestBack: () => void;
};

export type MobileDealDiscardGuardHandle = {
    requestClose: () => void;
};

type MobileDealDiscardGuardProps = {
    active: boolean;
    hasUnsavedChanges: () => boolean;
    disabled: boolean;
    onBack: () => void;
    onClose: () => void;
    children?: (controls: MobileDealDiscardControls) => React.ReactNode;
};

/** Owns the embedded deal composer's discard decision across Back, Close, and drawer dismissal. */
const MobileDealDiscardGuard = forwardRef<MobileDealDiscardGuardHandle, MobileDealDiscardGuardProps>(
    function MobileDealDiscardGuard({ active, hasUnsavedChanges, disabled, onBack, onClose, children }, ref) {
        const [requestedExit, setRequestedExit] = useState<'back' | 'close'>('close');
        const finishExit = useCallback(() => {
            if (requestedExit === 'back') onBack();
            else onClose();
        }, [onBack, onClose, requestedExit]);
        const readActiveDirtyState = useCallback(
            () => active && hasUnsavedChanges(),
            [active, hasUnsavedChanges],
        );
        const guard = useUnsavedChangesGuard({
            isDirty: readActiveDirtyState,
            enabled: active && !disabled,
            onClose: finishExit,
        });

        const requestExit = useCallback((exit: 'back' | 'close') => {
            if (disabled) return;
            if (!active || !hasUnsavedChanges()) {
                if (exit === 'back') onBack();
                else onClose();
                return;
            }
            setRequestedExit(exit);
            guard.requestClose();
        }, [active, disabled, guard, hasUnsavedChanges, onBack, onClose]);

        const handleOpenChange = useCallback((next: boolean) => {
            if (next || disabled) return;
            if (!active || !hasUnsavedChanges()) {
                onClose();
                return;
            }
            setRequestedExit('close');
            guard.onOpenChange(next);
        }, [active, disabled, guard, hasUnsavedChanges, onClose]);

        const requestBack = useCallback(() => requestExit('back'), [requestExit]);
        const requestClose = useCallback(() => requestExit('close'), [requestExit]);
        useImperativeHandle(ref, () => ({ requestClose }), [requestClose]);

        return (
            <>
                {children?.({ handleOpenChange, requestBack })}
                <ConfirmDiscardDialog
                    open={guard.confirm.open}
                    onKeepEditing={guard.confirm.onKeepEditing}
                    onDiscard={guard.confirm.onDiscard}
                />
            </>
        );
    },
);

export default MobileDealDiscardGuard;
