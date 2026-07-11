'use client';

import { useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';
import { XMarkIcon } from '@heroicons/react/24/outline';

import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { fieldInputClass } from '@/components/ui/dialog-status-cover';
import { cn } from '@/lib/utils';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { getShares, shareRecord, unshareRecord } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { Share } from '@/app/lib/types';

type Props = {
    type: 'company' | 'person' | 'pipeline';
    entityId: number;
    entityName: string;
    open: boolean;
    onOpenChange: (open: boolean) => void;
};

export default function ShareDialog({ type, entityId, entityName, open, onOpenChange }: Props) {
    const t = useTranslations('ShareDialog');
    const { workspaces, activeWorkspaceId } = useWorkspace();
    const [shares, setShares] = useState<Share[]>([]);
    const [loading, setLoading] = useState(true);
    const [target, setTarget] = useState('');
    const [sharing, setSharing] = useState(false);
    const [busyWorkspaceId, setBusyWorkspaceId] = useState<number | null>(null);

    useEffect(() => {
        if (!open) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            try {
                const loaded = await getShares(type, entityId);
                if (!cancelled) setShares(loaded);
            } catch (err) {
                if (!cancelled) toastError(err instanceof Error ? err.message : t('loadFailed'));
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [open, type, entityId, t]);

    const sharedIds = new Set(shares.map((s) => s.workspaceId));
    const targets = workspaces.filter((w) => w.id !== activeWorkspaceId && !sharedIds.has(w.id));

    const share = async () => {
        const workspaceId = Number(target);
        if (!workspaceId || sharing) return;
        setSharing(true);
        try {
            await shareRecord(type, entityId, workspaceId);
            const loaded = await getShares(type, entityId);
            setShares(loaded);
            setTarget('');
            toastSuccess(t('shared'));
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('shareFailed'));
        } finally {
            setSharing(false);
        }
    };

    const revoke = async (workspaceId: number) => {
        setBusyWorkspaceId(workspaceId);
        try {
            await unshareRecord(type, entityId, workspaceId);
            setShares((prev) => prev.filter((s) => s.workspaceId !== workspaceId));
            toastSuccess(t('revoked'));
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('revokeFailed'));
        } finally {
            setBusyWorkspaceId(null);
        }
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent className="sm:max-w-md">
                <DialogHeader>
                    <DialogTitle>{t('title', { name: entityName })}</DialogTitle>
                    <DialogDescription>{t('description')}</DialogDescription>
                </DialogHeader>

                <div className="space-y-4">
                    <div>
                        <h3 className="mb-2 text-sm font-medium text-foreground">{t('sharedWith')}</h3>
                        {loading ? (
                            <div className="h-10 animate-pulse rounded-lg bg-muted" />
                        ) : shares.length === 0 ? (
                            <p className="rounded-lg border border-dashed border-border px-3 py-4 text-center text-sm text-muted-foreground">
                                {t('noShares')}
                            </p>
                        ) : (
                            <ul className="divide-y divide-border overflow-hidden rounded-lg border border-border">
                                {shares.map((s) => (
                                    <li key={s.workspaceId} className="flex items-center justify-between gap-2 px-3 py-2">
                                        <span className="truncate text-sm text-foreground">{s.workspaceName}</span>
                                        <button
                                            type="button"
                                            onClick={() => revoke(s.workspaceId)}
                                            disabled={busyWorkspaceId === s.workspaceId}
                                            aria-label={t('revoke')}
                                            className="rounded-md p-1 text-muted-foreground transition hover:bg-destructive/10 hover:text-destructive disabled:opacity-50"
                                        >
                                            {busyWorkspaceId === s.workspaceId ? (
                                                <Loader2Icon className="size-4 animate-spin" />
                                            ) : (
                                                <XMarkIcon className="size-4" />
                                            )}
                                        </button>
                                    </li>
                                ))}
                            </ul>
                        )}
                    </div>

                    <div className="flex gap-2">
                        <select
                            value={target}
                            onChange={(e) => setTarget(e.target.value)}
                            aria-label={t('shareWith')}
                            disabled={targets.length === 0}
                            className={cn(fieldInputClass, 'flex-1 cursor-pointer px-3 disabled:opacity-50')}
                        >
                            <option value="">{targets.length === 0 ? t('noTargets') : t('shareWith')}</option>
                            {targets.map((w) => (
                                <option key={w.id} value={w.id}>
                                    {w.name}
                                </option>
                            ))}
                        </select>
                        <Button
                            onClick={share}
                            variant="brand"
                            disabled={sharing || !target}
                            className="min-w-20 shadow-sm transition"
                        >
                            {sharing ? <Loader2Icon className="size-4 animate-spin" /> : t('shareButton')}
                        </Button>
                    </div>
                </div>
            </DialogContent>
        </Dialog>
    );
}
