'use client';

import { useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';
import { Squares2X2Icon } from '@heroicons/react/24/outline';

import {
    Dialog,
    DialogContent,
    DialogHeader,
    DialogTitle,
    DialogDescription,
    DialogFooter,
    DialogClose,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import {
    DialogStatusCover,
    resolveDialogStatus,
    fieldInputClass,
    fieldErrorClass,
    fieldLeadIconClass,
} from '@/components/ui/dialog-status-cover';
import { cn } from '@/lib/utils';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { toastError, toastSuccess } from '@/app/lib/toast';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
};

export default function NewWorkspaceDialog({ open, onOpenChange }: Props) {
    const t = useTranslations('WorkspaceSwitcher');
    const { create } = useWorkspace();
    const { fieldErrors, setFieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } =
        useFieldErrors();

    const [name, setName] = useState('');
    const [isCreating, setIsCreating] = useState(false);
    const [succeeded, setSucceeded] = useState(false);

    // Reset everything whenever the dialog closes so it reopens clean.
    useEffect(() => {
        if (!open) {
            setName('');
            setIsCreating(false);
            setSucceeded(false);
            resetFieldErrors();
        }
    }, [open, resetFieldErrors]);

    const hasErrors = Object.keys(fieldErrors).length > 0;
    const status = resolveDialogStatus({ isLoading: isCreating, hasErrors, isSuccess: succeeded });

    const handleOpenChange = (next: boolean) => {
        if (!next && (isCreating || succeeded)) return; // hold open through submit + success band
        onOpenChange(next);
    };

    const focusName = () =>
        requestAnimationFrame(() => document.getElementById('workspace-name')?.focus());

    const handleCreate = async () => {
        resetFieldErrors();
        const trimmed = name.trim();
        if (!trimmed) {
            setFieldErrors({ name: t('nameRequired') });
            focusName();
            return;
        }
        setIsCreating(true);
        try {
            await create(trimmed);
            setIsCreating(false);
            setSucceeded(true);
            toastSuccess(t('created'));
            // Hold on the success-green band briefly before closing.
            setTimeout(() => onOpenChange(false), 900);
        } catch (err) {
            setIsCreating(false);
            if (!captureFieldErrors(err)) toastError(t('createFailed'));
            focusName();
        }
    };

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <DialogStatusCover status={status} />

                <div className="px-6 pb-6">
                    <DialogHeader className="ncd-rise -mt-12 mb-5" style={{ animationDelay: '40ms' }}>
                        <DialogTitle className="text-xl font-semibold tracking-tight">
                            {t('createTitle')}
                        </DialogTitle>
                        <DialogDescription>{t('createDescription')}</DialogDescription>
                    </DialogHeader>

                    <form
                        onSubmit={(e) => {
                            e.preventDefault();
                            if (isCreating || succeeded) return;
                            handleCreate();
                        }}
                        className="grid gap-5"
                    >
                        <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '90ms' }}>
                            <Label htmlFor="workspace-name">{t('nameLabel')}</Label>
                            <div className="group relative">
                                <Squares2X2Icon className={fieldLeadIconClass} />
                                <input
                                    id="workspace-name"
                                    type="text"
                                    value={name}
                                    onChange={(e) => {
                                        setName(e.target.value);
                                        clearError('name');
                                    }}
                                    className={cn(fieldInputClass, 'pl-9 pr-3', fieldErrors.name && fieldErrorClass)}
                                    placeholder={t('namePlaceholder')}
                                    aria-invalid={Boolean(fieldErrors.name)}
                                    aria-describedby={fieldErrors.name ? 'workspace-name-error' : undefined}
                                    autoFocus
                                    maxLength={128}
                                />
                            </div>
                            {fieldErrors.name && (
                                <p id="workspace-name-error" className="text-sm text-destructive">
                                    {fieldErrors.name}
                                </p>
                            )}
                        </div>

                        <DialogFooter className="ncd-rise mt-5" style={{ animationDelay: '140ms' }}>
                            <DialogClose asChild>
                                <Button type="button" variant="outline" disabled={isCreating || succeeded}>
                                    {t('cancel')}
                                </Button>
                            </DialogClose>
                            <Button
                                type="submit"
                                disabled={isCreating || succeeded}
                                variant="brand"
                                className="min-w-24 shadow-sm transition hover:shadow-md"
                            >
                                {isCreating ? <Loader2Icon className="size-4 animate-spin" /> : t('createButton')}
                            </Button>
                        </DialogFooter>
                    </form>
                </div>
            </DialogContent>
        </Dialog>
    );
}
